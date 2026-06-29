(ns tsukuru.py.agent
  "tsukuru 作 — B2B factory-direct ordering cell. cljc SSoT (ADR-2606160842 py->clj port wave; the
  py/agent.py origin and the .clj port were pruned). Four handlers over the
  production lifecycle: handle-discover (spec → capability-match factory candidates), handle-
  compliance (G16 treaty + yabai screening, never auto-pass), handle-production (BTO/MTO/CTO order
  state machine, G14 member-principal, G2 SBT-gated), handle-qc (pass/fail→quarantine/rework), +
  build-settlement-intent (G7 10% tithe, :intent unless member-signed G15). Pure compute; the
  Murakumo llm rerank (_llm_rank_factories) is the omitted leg — a no-op when llm is absent."
  (:require [clojure.string :as str]))

(def TITHE-BPS 1000)
(def ^:private FULFILLMENT-MODES ["bto" "mto" "cto"])
(def ^:private ORDER-FLOW ["draft" "placed" "dispatched" "in-production" "qc" "ready" "shipped"])

(defn- tokens
  "Split on any non-alphanumeric into lowercase tokens ('cnc-5axis' → {cnc, 5axis})."
  [s]
  (set (re-seq #"[a-z0-9]+" (str/lower-case s))))

(defn- capability-match
  "Token-overlap capability scoring (a capability counts if any token appears in the spec)."
  [spec factory]
  (let [spec-tokens (tokens spec)]
    (count (filter (fn [cap] (some #(contains? spec-tokens %) (tokens cap))) (get factory "capabilities" [])))))

(defn- llm-rank-factories
  "Murakumo-only rerank by genuine fit. The llm host binding is the omitted leg → no-op."
  [_spec cands]
  cands)

(defn handle-discover [state]
  (let [spec (get state "spec" "")
        factories (get state "factories" [])
        scored (vec (sort-by #(capability-match spec %) > factories))
        cands (let [c (filterv #(> (capability-match spec %) 0) scored)] (if (seq c) c scored))]
    (merge state {"candidates" (llm-rank-factories spec cands)})))

;; ── compliance (G16) ──────────────────────────────────────────────────────────
(defn screen-entity
  "yabai ScreenEntity (sanctions / denied-party). screen-fn injected for test/host."
  ([factory-did] (screen-entity factory-did nil))
  ([factory-did screen-fn]
   (if screen-fn
     (screen-fn factory-did)
     {"clear" nil "note" "yabai ScreenEntity not wired (R0); treat as pending"})))

(defn resolve-treaty
  "treaty.etzhayyim.com FTA/EPA authority-chain resolution."
  ([buyer-country factory-country] (resolve-treaty buyer-country factory-country nil))
  ([buyer-country factory-country treaty-fn]
   (cond
     treaty-fn (treaty-fn buyer-country factory-country)
     (and (seq buyer-country) (= buyer-country factory-country)) {"agreement" "domestic" "tariff_bps" 0}
     :else {"agreement" nil "note" "cross-border treaty not wired (R0)"})))

(defn handle-compliance
  ([state] (handle-compliance state nil nil))
  ([state screen-fn treaty-fn]
   (let [screen (screen-entity (get state "factoryDid" "") screen-fn)
         treaty (resolve-treaty (get state "buyerCountry" "") (get state "factoryCountry" "") treaty-fn)]
     (cond
       (= (get screen "clear") false)
       (merge state {"complianceState" "rejected" "complianceNote" (str "yabai denied-party hit: " (get screen "note" ""))})
       (= (get screen "clear") true)
       (merge state {"complianceState" "passed" "complianceNote" (str "yabai clear; treaty=" (get treaty "agreement"))})
       :else
       (merge state {"complianceState" "pending" "complianceNote" (str "screening unresolved: " (get screen "note" "") " (G16, no auto-pass)")})))))

;; ── production (order state machine) ──────────────────────────────────────────
(defn check-sbt-eligibility
  "Member buyer must hold an active Adherent SBT (§3 / G2)."
  [buyer-did sbt-registry]
  (let [active (boolean (get-in sbt-registry [buyer-did "active"]))]
    {"eligible" active "reason" (if active "" "buyer lacks active Adherent SBT (§3/G2)")}))

(defn create-production-order
  "okaimono create-production-order entrypoint. Member = purchasing principal (G14)."
  ([buyer-did factory-did mode spec] (create-production-order buyer-did factory-did mode spec nil))
  ([buyer-did factory-did mode spec sbt-registry]
   (if-not (contains? (set FULFILLMENT-MODES) mode)
     {"state" "refused" "reason" (str "unknown fulfillment mode '" mode "'")}
     (let [elig (check-sbt-eligibility buyer-did (or sbt-registry {}))]
       (if-not (get elig "eligible")
         {"state" "refused" "reason" (get elig "reason")}
         {"buyerDid" buyer-did "factoryDid" factory-did "mode" mode "spec" spec
          "state" "placed" "complianceState" "pending"})))))

(defn advance-order
  "Advance one stage along ORDER-FLOW. Compliance must pass before dispatch (G16)."
  [order]
  (let [st (get order "state" "draft")]
    (cond
      (contains? #{"refused" "quarantine"} st) order
      (and (= st "placed") (not= (get order "complianceState") "passed"))
      (merge order {"blocked" "compliance not passed (G16)"})
      :else (let [idx (.indexOf ORDER-FLOW st)]
              (if (or (neg? idx) (>= (inc idx) (count ORDER-FLOW)))
                order
                (merge order {"state" (nth ORDER-FLOW (inc idx)) "blocked" nil}))))))

(defn- stage-pct [stage]
  (get {"draft" 0 "placed" 5 "dispatched" 15 "in-production" 60 "qc" 90 "ready" 98 "shipped" 100 "quarantine" 90} stage 0))

(defn handle-production [state]
  (let [order (or (get state "order")
                  (create-production-order (get state "buyerDid" "") (get state "factoryDid" "")
                                           (get state "mode" "bto") (get state "spec" "") (get state "sbtRegistry" {})))
        advanced (advance-order order)
        progress {"order" (get advanced "buyerDid" "") "stage" (get advanced "state")
                  "pct" (stage-pct (get advanced "state" "draft")) "timestamp" (get state "now" "")}]
    (merge state {"order" advanced "progress" progress})))

;; ── qc ──────────────────────────────────────────────────────────────────────
(defn handle-qc
  ([state] (handle-qc state nil))
  ([state inspect-fn]
   (let [order (get state "order" {})
         defects (or (if inspect-fn (inspect-fn order) (get state "defects")) [])
         [result order*] (if (seq defects)
                           (let [res (if (get state "reworkable" true) "rework" "fail")
                                 ns (if (= res "rework") "in-production" "quarantine")]
                             [res (merge order {"state" ns})])
                           ["pass" (merge order {"state" "ready"})])
         quality {"order" (get order* "buyerDid" "") "result" result "defects" defects
                  "inspectorDid" (get state "inspectorDid" "") "timestamp" (get state "now" "")}]
     (merge state {"order" order* "quality" quality}))))

(defn build-settlement-intent
  "USDC settlement split. 10% tithe → Public Fund; factory gets the net. Stops at :intent —
  broadcast needs a member signature (G15) + operator gate (G11)."
  ([gross-minor] (build-settlement-intent gross-minor nil))
  ([gross-minor buyer-sig-ref]
   (let [tithe (quot (* gross-minor TITHE-BPS) 10000)]
     {"rail" "usdc-base-l2" "grossMinor" gross-minor "titheMinor" tithe
      "factoryPayoutMinor" (- gross-minor tithe) "titheRouter" "50-infra/etzhayyim-tithe-router"
      "state" (if buyer-sig-ref "executed" "intent") "buyerSigRef" (or buyer-sig-ref "")})))
