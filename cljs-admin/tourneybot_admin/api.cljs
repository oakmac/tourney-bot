(ns tourneybot-admin.api
  "Function wrapper around API calls."
  (:require
    [cljsjs.jquery]
    [tourneybot.util :refer [js-log log]]))

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

(def api-url "../api.php")
(def json-stringify js/JSON.stringify)

;;------------------------------------------------------------------------------
;; API Methods
;;------------------------------------------------------------------------------

(defn check-password
  "Checks that a password is valid."
  [pwd success-fn error-fn]
  (.ajax js/jQuery
    (js-obj
      "data" (js-obj "method" "check-password"
                     "password" pwd)
      "dataType" "text"
      "error" error-fn
      "method" "post"
      "success" (fn [response-txt]
                  (if (= response-txt "true")
                    (success-fn)
                    (error-fn)))
      "url" api-url)))

(defn update-games!
  "Upload the games to tournament.json"
  [password games success-fn error-fn]
  (.ajax js/jQuery
    (js-obj
      "data" (js-obj "method" "update-games"
                     "games-json" (-> games clj->js json-stringify)
                     "password" password)
      "dataType" "text"
      "error" error-fn
      "method" "post"
      "success" (fn [response-txt]
                  (if (= response-txt "true")
                    (success-fn)
                    (error-fn)))
      "url" api-url)))
