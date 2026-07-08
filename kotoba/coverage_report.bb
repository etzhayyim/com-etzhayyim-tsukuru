#!/usr/bin/env bb
;; tsukuru 作 — candidates.edn coverage report (planning aid for the next batch)
;; Usage: bb kotoba/coverage_report.bb [path-to-candidates.edn]
;; Prints per-country and per-ISIC entry counts, sorted thin-to-thick, so the
;; next research batch can pick under-represented economies/industries without
;; hand-rolling a bb -e query first.

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def path (or (first *command-line-args*)
              (str (.getParent (io/file *file*)) "/candidates.edn")))

(defn- print-table [title freqs]
  (println (str "\n" title " (" (count freqs) " distinct, thinnest first)"))
  (doseq [[k n] (sort-by val freqs)]
    (println (format "  %-6s %d" k n))))

(let [entries (edn/read-string (slurp path))
      by-country (frequencies (map :factory/country entries))
      by-isic (frequencies (map :factory/isic entries))]
  (println (str (count entries) " entries, " (count by-country) " countries, "
                (count by-isic) " ISIC divisions"))
  (print-table "By country" by-country)
  (print-table "By ISIC division" by-isic))
