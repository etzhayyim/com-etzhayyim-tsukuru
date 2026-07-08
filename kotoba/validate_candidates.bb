#!/usr/bin/env bb
;; tsukuru 作 — candidates.edn consistency check (G10 sourcing honesty)
;; Usage: bb kotoba/validate_candidates.bb [path-to-candidates.edn]
;; Exits non-zero on any violation so this can gate CI the same way
;; gen-west-manifest.bb --check gates manifest.

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def path (or (first *command-line-args*)
              (str (.getParent (io/file *file*)) "/candidates.edn")))

(defn fail [msg]
  (println "FAIL:" msg)
  (System/exit 1))

(let [entries (try (edn/read-string (slurp path))
                    (catch Exception e
                      (fail (str "candidates.edn does not parse as EDN: " (.getMessage e)))))]
  (when-not (vector? entries)
    (fail "top-level form must be a vector of entity maps"))

  (let [dids (map :factory/did entries)]
    (when (not= (count dids) (count (distinct dids)))
      (fail (str "duplicate :factory/did found — "
                 (->> dids (frequencies) (filter #(> (val %) 1)) (map key) (into []))))))

  ;; catches the same company added twice under different :factory/did slugs — the DID check
  ;; above only catches identical ids, not identical companies (this happened for real research
  ;; picks re-surfacing across batches 9-13; a normalized-name collision here would have caught
  ;; them even if a different slug had been used by mistake)
  (let [norm (fn [s] (-> s clojure.string/lower-case (clojure.string/replace #"[^a-z0-9]" "")))
        by-norm (group-by (comp norm :factory/display-name) entries)
        dupes (filter #(> (count (val %)) 1) by-norm)]
    (when (seq dupes)
      (fail (str "near-duplicate :factory/display-name (same company, different :factory/did) — "
                 (mapv #(mapv :factory/display-name (val %)) dupes)))))

  (doseq [[i e] (map-indexed vector entries)]
    (let [did (:factory/did e)]
      (when (or (nil? did) (re-find #"^did:web:tsukuru\.etzhayyim\.com/" did))
        (fail (str "entry " i " (" (:factory/display-name e) "): :factory/did must be a "
                   "\"candidate:manufacturer-directory/...\" id, never a did:web:tsukuru.etzhayyim.com/... "
                   "path — this file is public-directory reference data only, not the onboarded registry")))
      (when-not (= :public-directory (:factory/sourcing e))
        (fail (str "entry " i " (" (:factory/display-name e) "): :factory/sourcing must be "
                   ":public-directory in this file")))
      (when-not (= :unknown (:factory/labor-provenance e))
        (fail (str "entry " i " (" (:factory/display-name e) "): :factory/labor-provenance must be "
                   ":unknown — public corporate facts don't establish labor/ESG conditions (G8)")))
      (when (empty? (:factory/source-url e))
        (fail (str "entry " i " (" (:factory/display-name e) "): missing :factory/source-url citation")))
      (when-not (re-matches #"[A-Z]{2}" (:factory/country e))
        (fail (str "entry " i " (" (:factory/display-name e) "): :factory/country must be ISO 3166-1 alpha-2")))
      (when-not (re-matches #"C\d{2}" (:factory/isic e))
        (fail (str "entry " i " (" (:factory/display-name e) "): :factory/isic must match Cnn")))
      (when (empty? (:factory/capabilities e))
        (fail (str "entry " i " (" (:factory/display-name e) "): :factory/capabilities must be non-empty")))))

  (println (str "OK — " (count entries) " entries, "
                (count (distinct (map :factory/country entries))) " countries, "
                (count (distinct (map :factory/isic entries))) " ISIC divisions, "
                "0 duplicate DIDs, all sourced.")))
