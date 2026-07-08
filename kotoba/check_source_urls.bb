#!/usr/bin/env bb
;; tsukuru 作 — candidates.edn source-url reachability check (maintenance, NOT a CI gate)
;; Usage: bb kotoba/check_source_urls.bb [path-to-candidates.edn]
;;
;; HTTP checks are inherently flaky in CI (rate-limiting, bot-blocking, transient
;; outages) so this is a periodic/manual maintenance script, not wired into
;; validate-candidates.yml. A non-2xx/3xx result here means "worth a human
;; glance", not "this entry is fabricated" — G10 sourcing honesty is about the
;; fact being real and citable, not about the URL never rotting.

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[babashka.process :refer [shell]])

(def path (or (first *command-line-args*)
              (str (.getParent (io/file *file*)) "/candidates.edn")))

(defn http-status [url]
  (try
    (-> (shell {:out :string :err :string :continue true}
               "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}"
               "-L" "--max-time" "8" "-A" "Mozilla/5.0 (tsukuru-candidates-link-check)"
               url)
        :out
        clojure.string/trim)
    (catch Exception e (str "ERR:" (.getMessage e)))))

(let [entries (edn/read-string (slurp path))
      total (count entries)]
  (println (str "Checking " total " source URLs (sequential, ~10s timeout each — this takes a while)..."))
  (let [results (map-indexed
                  (fn [i e]
                    (let [url (:factory/source-url e)
                          status (http-status url)
                          ok? (re-matches #"[23]\d\d" status)]
                      (when (zero? (mod (inc i) 50))
                        (println (str "  ..." (inc i) "/" total)))
                      {:name (:factory/display-name e) :url url :status status :ok ok?}))
                  entries)
        bad (remove :ok results)]
    (println (str "\n" (- total (count bad)) "/" total " returned 2xx/3xx."))
    (when (seq bad)
      (println (str (count bad) " worth a manual glance (not necessarily broken — could be "
                     "bot-blocking or a transient outage):"))
      (doseq [{:keys [name url status]} bad]
        (println (str "  [" status "] " name " — " url))))))
