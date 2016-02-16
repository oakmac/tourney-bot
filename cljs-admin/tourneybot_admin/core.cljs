(ns tourneybot-admin.core
  (:require
    [tourneybot.util :refer [by-id js-log log]]))

;;------------------------------------------------------------------------------
;; Init
;;------------------------------------------------------------------------------

(defn- init!
  "Initialize the Admin page."
  []
  (js-log "admin init"))

(init!)
