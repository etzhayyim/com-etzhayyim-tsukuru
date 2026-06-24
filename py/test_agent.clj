#!/usr/bin/env bb
;; tsukuru 作 — agent cell tests (babashka, no kotoba host, no network, no LLM).
;;
;; ADR-2605202800 Phase 4. Exercises the 4 handlers + settlement + gates with injected
;; functions so the suite runs offline (Murakumo-only invariant untouched; G5).
;;
;;   bb --classpath 20-actors 20-actors/tsukuru/py/test_agent.clj

(ns tsukuru.py.test-agent
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [tsukuru.py.agent :as agent]))

;; ── 1. discover ───────────────────────────────────────────────────────────────
(deftest test-discover-ranks-by-capability
  (testing "discover puts best capability match first"
    (let [out (agent/handle-discover
                {:spec      "5-axis CNC aluminum bracket, anodized black"
                 :factories [{:factoryDid "inj" :capabilities ["injection-molding"]}
                             {:factoryDid "cnc" :capabilities ["cnc-5axis" "anodizing"]}]})]
      (is (= "cnc" (:factoryDid (first (:candidates out))))))))

;; ── 2. SBT gate ───────────────────────────────────────────────────────────────
(deftest test-sbt-gate-refuses-non-member
  (testing "non-SBT buyer refused (§3/G2)"
    (let [order (agent/create-production-order "did:web:stranger" "f1" "bto" "x" {})]
      (is (= "refused" (:state order))))))

;; ── 3. unknown mode ───────────────────────────────────────────────────────────
(deftest test-unknown-mode-refused
  (testing "unknown fulfillment mode refused"
    (let [reg   {"did:web:m" {:active true}}
          order (agent/create-production-order "did:web:m" "f1" "zzz" "x" reg)]
      (is (= "refused" (:state order))))))

;; ── 4. compliance — no auto-pass when unresolved ──────────────────────────────
(deftest test-compliance-no-auto-pass-when-unresolved
  (testing "unresolved screening never auto-passes (G16)"
    (let [out (agent/handle-compliance
                {:factoryDid "f1" :buyerCountry "JP" :factoryCountry "TW"})]
      (is (= "pending" (:complianceState out))))))

;; ── 5. compliance — denied party rejects ──────────────────────────────────────
(deftest test-compliance-denied-party-rejects
  (testing "denied-party hit rejects (G16)"
    (let [out (agent/handle-compliance
                {:factoryDid "bad"}
                (fn [_did] {:clear false :note "OFAC SDN"})
                nil)]
      (is (= "rejected" (:complianceState out))))))

;; ── 6. advance blocked until compliance passed ────────────────────────────────
(deftest test-advance-blocked-until-compliance-passed
  (testing "dispatch blocked until compliance passes (G16)"
    (let [order {:state "placed" :complianceState "pending"}
          out   (agent/advance-order order)]
      (is (and (some? (:blocked out)) (= "placed" (:state out)))))))

;; ── 7. advance proceeds after compliance ──────────────────────────────────────
(deftest test-advance-proceeds-after-compliance
  (testing "advances to dispatched after compliance pass"
    (let [order {:state "placed" :complianceState "passed"}
          out   (agent/advance-order order)]
      (is (= "dispatched" (:state out))))))

;; ── 8. QC fail → quarantine ───────────────────────────────────────────────────
(deftest test-qc-fail-diverts-to-quarantine
  (testing "QC fail → quarantine (G17)"
    (let [out (agent/handle-qc
                {:order {:state "qc"} :defects ["crack"] :reworkable false})]
      (is (= "quarantine" (get-in out [:order :state]))))))

;; ── 9. QC pass → ready ────────────────────────────────────────────────────────
(deftest test-qc-pass-marks-ready
  (testing "QC pass → ready"
    (let [out (agent/handle-qc {:order {:state "qc"} :defects []})]
      (is (and (= "ready" (get-in out [:order :state]))
               (= "pass" (get-in out [:quality :result])))))))

;; ── 10. settlement tithe split ────────────────────────────────────────────────
(deftest test-settlement-tithe-split
  (testing "10% tithe split + stops at intent (G7/G11)"
    (let [s (agent/build-settlement-intent 500000000)]
      (is (= 50000000   (:titheMinor s)))
      (is (= 450000000  (:factoryPayoutMinor s)))
      (is (= "intent"   (:state s)))
      (is (= "usdc-base-l2" (:rail s))))))

;; ── 11. settlement executed only with member sig ──────────────────────────────
(deftest test-settlement-executed-only-with-member-sig
  (testing "settlement executes only with member signature (G15)"
    (let [s (agent/build-settlement-intent 1000000 "0xmembersig")]
      (is (= "executed" (:state s))))))

;; ── runner ────────────────────────────────────────────────────────────────────
(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (clojure.test/run-tests 'tsukuru.py.test-agent)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
