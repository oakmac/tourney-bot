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
      "data" (js-obj "password" pwd)
      "dataType" "text"
      "error" error-fn
      "method" "post"
      "success" (fn [response-txt]
                  (if (= response-txt "true")
                    (success-fn)
                    (error-fn)))
      "url" "../api/check-password.php")))

(defn update-event!
  "Upload the new tournament state."
  [password state success-fn error-fn]
  (.ajax js/jQuery
    (js-obj
      "data" (js-obj "data" (-> state clj->js json-stringify)
                     "password" password)
      "dataType" "text"
      "error" error-fn
      "method" "post"
      "success" success-fn
      "url" "../api/update.php")))
