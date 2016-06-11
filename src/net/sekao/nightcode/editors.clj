(ns net.sekao.nightcode.editors
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.util.response :refer [redirect]]
            [ring.util.request :refer [body-string]]
            [clojure.spec :as s :refer [fdef]]
            [net.sekao.nightcode.shortcuts :as shortcuts]
            [net.sekao.nightcode.utils :as u]
            [net.sekao.nightcode.spec :as spec])
  (:import [javafx.fxml FXMLLoader]
           [javafx.scene.web WebEngine]
           [java.io File]))

(def ^:const clojure-exts #{"boot" "clj" "cljc" "cljs" "cljx" "edn" "pxi"})
(def ^:const wrap-exts #{"md" "txt"})

(fdef eval-form
  :args (s/cat :form-str string? :nspace spec/ns?)
  :ret vector?)
(defn eval-form [form-str nspace]
  (binding [*read-eval* false
            *ns* nspace]
    (try
      (refer-clojure)
      (let [form (read-string form-str)
            result (u/with-security (eval form))
            current-ns (if (and (coll? form) (= 'ns (first form)))
                         (-> form second create-ns)
                         *ns*)]
        [result current-ns])
      (catch Exception e [e *ns*]))))

(fdef eval-forms
  :args (s/cat :forms-str string?)
  :ret vector?)
(defn eval-forms [forms-str]
  (loop [forms (edn/read-string forms-str)
         results []
         nspace (create-ns 'clj.user)]
    (if-let [form (first forms)]
      (let [[result current-ns] (eval-form form nspace)
            result-str (if (instance? Exception result) [(.getMessage result)] (pr-str result))]
        (recur (rest forms) (conj results result-str) current-ns))
      results)))

(fdef handler
  :args (s/cat :request map?)
  :ret (s/nilable map?))
(defn handler [request]
  (case (:uri request)
    "/" (redirect "/index.html")
    "/eval" {:status 200
             :headers {"Content-Type" "text/plain"}
             :body (pr-str (eval-forms (body-string request)))}
    nil))

(fdef start-web-server!
  :args (s/cat)
  :ret integer?)
(defn start-web-server! []
  (-> handler
      (wrap-resource "public")
      (wrap-content-type)
      (run-jetty {:port 0 :join? false})
      .getConnectors
      (aget 0)
      .getLocalPort))

(fdef remove-editors!
  :args (s/cat :path string? :state spec/atom?))
(defn remove-editors! [^String path state-atom]
  (doseq [[editor-path pane] (:editor-panes @state-atom)]
    (when (u/parent-path? path editor-path)
      (swap! state-atom update :editor-panes dissoc editor-path)
      (shortcuts/hide-tooltips! pane)
      (-> pane .getParent .getChildren (.remove pane)))))

(definterface Bridge
  (onload []))

(fdef onload
  :args (s/cat :engine :clojure.spec/any :file spec/file? :clojure? spec/boolean?))
(defn onload [^WebEngine engine ^File file clojure?]
  ; set the page content
  (-> engine
      .getDocument
      (.getElementById "content")
      (.setTextContent (slurp file)))
  ; inject paren-soup
  (when clojure?
    (let [body (-> engine .getDocument (.getElementsByTagName "body") (.item 0))
          script (-> engine .getDocument (.createElement "script"))]
      (.setAttribute script "src" "paren-soup.js")
      (.appendChild body script))))

(fdef editor-pane
  :args (s/cat :state map? :file spec/file?)
  :ret spec/pane?)
(defn editor-pane [state file]
  (let [pane (FXMLLoader/load (io/resource "editor.fxml"))
        buttons (-> pane .getChildren (.get 0) .getChildren seq)
        webview (-> pane .getChildren (.get 1))
        engine (.getEngine webview)
        clojure? (-> file .getName u/get-extension clojure-exts some?)]
    (shortcuts/add-tooltips! buttons)
    (-> engine
        (.executeScript "window")
        (.setMember "java"
          (proxy [Bridge] []
            (onload []
              (try
                (onload engine file clojure?)
                (catch Exception e (.printStackTrace e)))))))
    (.load engine (str "http://localhost:"
                    (:web-port state)
                    (if clojure? "/index.html" "/index2.html")))
    pane))
