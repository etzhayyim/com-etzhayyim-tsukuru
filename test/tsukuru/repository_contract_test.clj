(ns tsukuru.repository-contract-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repository-root (.getCanonicalFile (io/file ".")))

(defn files-under [root]
  (->> (file-seq (io/file repository-root root))
       (filter #(.isFile %))))

(deftest canonical-edn-is-readable
  (doseq [file (files-under ".")
          :when (str/ends-with? (.getName file) ".edn")]
    (is (some? (edn/read-string (slurp file))) (.getPath file))))

(deftest external-contracts-live-under-wire
  (doseq [file (files-under ".")
          :let [path (.getPath file)]
          :when (re-find #"\.(?:json|jsonld|bpmn)$" path)]
    (is (or (str/includes? path (str java.io.File/separator "wire" java.io.File/separator))
            (str/ends-with? path (str java.io.File/separator ".well-known" java.io.File/separator "did.json")))
        path)))

(deftest deprecated-runtimes-are-absent
  (testing "Go, TinyGo, and shell sources are pruned"
    (doseq [file (files-under ".")]
      (is (not (re-find #"\.(?:go|sh)$" (.getName file))) (.getPath file)))))
