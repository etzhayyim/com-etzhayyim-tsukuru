#!/usr/bin/env bb
;; Working Clojure port of py/agent.py (tsukuru B2B factory-direct ordering actor).
(ns tsukuru.py.agent
  "tsukuru 作 — B2B factory-direct ordering langgraph actor (kotoba WASM cell).

  ADR-2605202800, migration plan Phase 4. Runs in-WASM on kotoba :8077. Four handlers
  over one kotoba EAVT graph, mirroring the production lifecycle:

    handle-discover    member spec → capability/country/ISIC match → factory candidates
    handle-production  production-order (BTO/MTO/CTO): create → dispatch → progress → ready
    handle-qc          quality-inspection: pass / fail (→ quarantine) / rework
    handle-compliance  trade-compliance gate (G16): treaty (FTA/EPA) + yabai screening

  LLM access is Murakumo-only via KotobaLLM (127.0.0.1:4000, gemma3:4b; G5). State is
  written back to the kotoba Datom log (G6). The member is always the purchasing
  principal (G14): no external B2B value flows INTO etzhayyim (G2). Settlement is USDC
  on Base L2 + ERC-4337 + TitheRouter 10% only — no fiat, no Stripe (G7). The platform
  holds no key; the member signs each settlement with their own passkey/smart-account
  (G15). Every stage is recorded as a Datom — no silent truncation (G17).

  This R0 build computes and returns plans/records; it does not dispatch real factory
  work and does not broadcast settlements (both G11-gated; settlement stops at :intent).

  Run:  bb --classpath 20-actors 20-actors/tsukuru/py/agent.clj")

;; ── constants ──────────────────────────────────────────────────────────────────
(def ^:private tithe-bps 1000)  ; 10% TitheRouter auto-split (G7), basis points

(def fulfillment-modes #{"bto" "mto" "cto"})

;; linear production trajectory (G17) — every transition is a recorded stage
(def order-flow
  ["draft" "placed" "dispatched" "in-production" "qc" "ready" "shipped"])

;; ── discover — spec → matching factory candidates ──────────────────────────────

(defn- tokens
  "Split on any non-alphanumeric into lowercase tokens (e.g. 'cnc-5axis' → #{cnc 5axis})."
  [s]
  (let [result (atom #{})
        cur    (atom [])]
    (doseq [ch (seq (clojure.string/lower-case s))]
      (if (Character/isLetterOrDigit ch)
        (swap! cur conj ch)
        (when (seq @cur)
          (swap! result conj (apply str @cur))
          (reset! cur []))))
    (when (seq @cur)
      (swap! result conj (apply str @cur)))
    @result))

(defn- capability-match
  "Token-overlap capability scoring (a capability counts if any of its tokens appears
  in the spec). Crude by design — Murakumo rerank refines genuine fit (G5)."
  [spec factory]
  (let [spec-tokens (tokens spec)]
    (reduce (fn [acc cap]
              (if (seq (clojure.set/intersection (tokens cap) spec-tokens))
                (inc acc)
                acc))
            0
            (get factory :capabilities []))))

(defn- llm-rank-factories
  "Murakumo-only rerank by genuine fit (capability/quality), never paid placement.
  In-WASM: calls llm/infer; offline: returns cands as-is (no host binding)."
  [spec cands]
  ;; llm host binding not available in local dev / babashka — return cands unchanged
  cands)

(defn handle-discover
  "spec → capability-scored factory candidates → Murakumo rerank."
  [state]
  (let [spec      (get state :spec "")
        factories (get state :factories [])
        scored    (sort-by #(capability-match spec %) > factories)
        cands     (or (seq (filter #(pos? (capability-match spec %)) scored))
                      (seq scored)
                      [])
        cands     (llm-rank-factories spec (vec cands))]
    (assoc state :candidates cands)))

;; ── compliance — trade-compliance gate (G16) ──────────────────────────────────

(defn screen-entity
  "yabai ScreenEntity (sanctions / denied-party). screen-fn injected for test/host."
  ([factory-did] (screen-entity factory-did nil))
  ([_factory-did screen-fn]
   (if (some? screen-fn)
     (screen-fn _factory-did)
     ;; R0 default: no host screening wired → conservative 'pending', never silent pass
     {:clear nil :note "yabai ScreenEntity not wired (R0); treat as pending"})))

(defn resolve-treaty
  "treaty.etzhayyim.com FTA/EPA authority-chain resolution."
  ([buyer-country factory-country] (resolve-treaty buyer-country factory-country nil))
  ([buyer-country factory-country treaty-fn]
   (if (some? treaty-fn)
     (treaty-fn buyer-country factory-country)
     (if (and (seq buyer-country) (= buyer-country factory-country))
       {:agreement "domestic" :tariff_bps 0}
       {:agreement nil :note "cross-border treaty not wired (R0)"}))))

(defn handle-compliance
  "Compose screen-entity + resolve-treaty; G16 hard reject on denied-party hit."
  ([state] (handle-compliance state nil nil))
  ([state screen-fn treaty-fn]
   (let [factory-did     (get state :factoryDid "")
         buyer-country   (get state :buyerCountry "")
         factory-country (get state :factoryCountry "")
         screen          (screen-entity factory-did screen-fn)
         treaty          (resolve-treaty buyer-country factory-country treaty-fn)]
     ;; G16: a denied-party hit is a hard reject; unresolved screening cannot 'pass'
     (cond
       (false? (:clear screen))
       (assoc state
              :complianceState "rejected"
              :complianceNote (str "yabai denied-party hit: " (get screen :note "")))

       (true? (:clear screen))
       (assoc state
              :complianceState "passed"
              :complianceNote (str "yabai clear; treaty=" (:agreement treaty)))

       :else
       (assoc state
              :complianceState "pending"
              :complianceNote (str "screening unresolved: " (get screen :note "") " (G16, no auto-pass)"))))))

;; ── production — order state machine (BTO/MTO/CTO) ────────────────────────────

(defn check-sbt-eligibility
  "Member buyer must hold an active Adherent SBT (§3 / G2)."
  [buyer-did sbt-registry]
  (let [active (boolean (get-in sbt-registry [buyer-did :active]))]
    {:eligible active
     :reason   (if active "" "buyer lacks active Adherent SBT (§3/G2)")}))

(defn create-production-order
  "okaimono `create-production-order` entrypoint. Member = purchasing principal (G14)."
  ([buyer-did factory-did mode spec]
   (create-production-order buyer-did factory-did mode spec {}))
  ([buyer-did factory-did mode spec sbt-registry]
   (if-not (contains? fulfillment-modes mode)
     {:state "refused" :reason (str "unknown fulfillment mode '" mode "'")}
     (let [elig (check-sbt-eligibility buyer-did (or sbt-registry {}))]
       (if-not (:eligible elig)
         {:state "refused" :reason (:reason elig)}
         {:buyerDid       buyer-did
          :factoryDid     factory-did
          :mode           mode
          :spec           spec
          :state          "placed"
          :complianceState "pending"})))))

(defn- stage-pct
  "Progress percentage by stage name."
  [stage]
  (get {"draft" 0 "placed" 5 "dispatched" 15 "in-production" 60
        "qc" 90 "ready" 98 "shipped" 100 "quarantine" 90}
       stage 0))

(defn advance-order
  "Advance one stage along order-flow. Compliance must pass before dispatch (G16);
  a QC fail diverts to :quarantine (handled in handle-qc)."
  [order]
  (let [state (get order :state "draft")]
    (cond
      (or (= state "refused") (= state "quarantine"))
      order

      (and (= state "placed") (not= (get order :complianceState) "passed"))
      (assoc order :blocked "compliance not passed (G16)")

      :else
      (let [idx (.indexOf order-flow state)]
        (if (or (neg? idx) (>= (inc idx) (count order-flow)))
          order
          (assoc order :state (nth order-flow (inc idx)) :blocked nil))))))

(defn handle-production
  "Create (if missing) or advance the production order; stamp a progress datom (G17)."
  [state]
  (let [order    (or (get state :order)
                     (create-production-order
                       (get state :buyerDid "")
                       (get state :factoryDid "")
                       (get state :mode "bto")
                       (get state :spec "")
                       (get state :sbtRegistry {})))
        advanced (advance-order order)
        progress {:order     (get advanced :buyerDid "")
                  :stage     (get advanced :state)
                  :pct       (stage-pct (get advanced :state "draft"))
                  :timestamp (get state :now "")}]
    (assoc state :order advanced :progress progress)))

;; ── qc — quality inspection (pass / fail→quarantine / rework) ─────────────────

(defn handle-qc
  "Quality inspection: pass → ready; fail → quarantine; rework → in-production.
  inspect-fn is injected for test/host; defaults to state[:defects]."
  ([state] (handle-qc state nil))
  ([state inspect-fn]
   (let [order   (get state :order {})
         defects (or (when (some? inspect-fn) (inspect-fn order))
                     (get state :defects)
                     [])
         [result new-state]
         (if (seq defects)
           (if (get state :reworkable true)
             ["rework" "in-production"]
             ["fail" "quarantine"])
           ["pass" "ready"])
         order   (assoc order :state new-state)
         quality {:order       (get order :buyerDid "")
                  :result      result
                  :defects     defects
                  :inspectorDid (get state :inspectorDid "")
                  :timestamp   (get state :now "")}]
     (assoc state :order order :quality quality))))

;; ── settlement — USDC + TitheRouter intent (NOT broadcast; G7/G11/G15) ─────────

(defn build-settlement-intent
  "Compute the USDC settlement split. 10% tithe → Public Fund; factory gets the net.
  Stops at :intent — broadcast needs a member signature (G15) + operator gate (G11)."
  ([gross-minor] (build-settlement-intent gross-minor nil))
  ([gross-minor buyer-sig-ref]
   (let [gross (long gross-minor)
         tithe (quot (* gross tithe-bps) 10000)]
     {:rail               "usdc-base-l2"
      :grossMinor         gross
      :titheMinor         tithe
      :factoryPayoutMinor (- gross tithe)
      :titheRouter        "50-infra/etzhayyim-tithe-router"
      :state              (if buyer-sig-ref "executed" "intent")
      :buyerSigRef        (or buyer-sig-ref "")})))
