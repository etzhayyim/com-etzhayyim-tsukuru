(ns tsukuru.py.test-agent
  "tsukuru 作 — agent cell tests. 1:1 port of py/test_agent.py (custom harness → clojure.test).
  Offline: discover capability ranking, SBT gate (§3/G2), unknown-mode refusal, compliance no-auto-
  pass + denied-party reject (G16), order advance gating, QC fail→quarantine / pass→ready, USDC +
  tithe settlement (G7/G11/G15)."
  (:require [clojure.test :refer [deftest is]]
            [tsukuru.py.agent :as agent]))

(deftest test-discover-ranks-by-capability
  (let [out (agent/handle-discover {"spec" "5-axis CNC aluminum bracket, anodized black"
                                    "factories" [{"factoryDid" "inj" "capabilities" ["injection-molding"]}
                                                 {"factoryDid" "cnc" "capabilities" ["cnc-5axis" "anodizing"]}]})]
    (is (= "cnc" (get (first (get out "candidates")) "factoryDid")))))

(deftest test-sbt-gate-refuses-non-member
  (is (= "refused" (get (agent/create-production-order "did:web:stranger" "f1" "bto" "x" {}) "state"))))

(deftest test-unknown-mode-refused
  (let [reg {"did:web:m" {"active" true}}]
    (is (= "refused" (get (agent/create-production-order "did:web:m" "f1" "zzz" "x" reg) "state")))))

(deftest test-compliance-no-auto-pass-when-unresolved
  (let [out (agent/handle-compliance {"factoryDid" "f1" "buyerCountry" "JP" "factoryCountry" "TW"})]
    (is (= "pending" (get out "complianceState")))))

(deftest test-compliance-denied-party-rejects
  (let [out (agent/handle-compliance {"factoryDid" "bad"} (fn [_did] {"clear" false "note" "OFAC SDN"}) nil)]
    (is (= "rejected" (get out "complianceState")))))

(deftest test-advance-blocked-until-compliance-passed
  (let [out (agent/advance-order {"state" "placed" "complianceState" "pending"})]
    (is (some? (get out "blocked")))
    (is (= "placed" (get out "state")))))

(deftest test-advance-proceeds-after-compliance
  (let [out (agent/advance-order {"state" "placed" "complianceState" "passed"})]
    (is (= "dispatched" (get out "state")))))

(deftest test-qc-fail-diverts-to-quarantine
  (let [out (agent/handle-qc {"order" {"state" "qc"} "defects" ["crack"] "reworkable" false})]
    (is (= "quarantine" (get-in out ["order" "state"])))))

(deftest test-qc-pass-marks-ready
  (let [out (agent/handle-qc {"order" {"state" "qc"} "defects" []})]
    (is (= "ready" (get-in out ["order" "state"])))
    (is (= "pass" (get-in out ["quality" "result"])))))

(deftest test-settlement-tithe-split
  (let [s (agent/build-settlement-intent 500000000)]
    (is (= 50000000 (get s "titheMinor")))
    (is (= 450000000 (get s "factoryPayoutMinor")))
    (is (= "intent" (get s "state")))
    (is (= "usdc-base-l2" (get s "rail")))))

(deftest test-settlement-executed-only-with-member-sig
  (is (= "executed" (get (agent/build-settlement-intent 1000000 "0xmembersig") "state"))))
