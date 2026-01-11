(require 'org)

(defun my/org-normalize-rating-and-save-dads-title ()
  "Convert +/- ratings to numeric and store original headline in DADS_TITLE."
  (interactive)
  (org-map-entries
   (lambda ()
     ;; ---- Save original headline ----
     (let ((existing (org-entry-get (point) "DADS_TITLE")))
       (unless existing
         (org-entry-put
          (point)
          "DADS_TITLE"
          (org-get-heading t t t t))))

     ;; ---- Normalize rating ----
     (let ((rating (org-entry-get (point) "RATING")))
       (when (and rating (not (string-blank-p rating)))
         (let* ((clean (string-trim rating))
                (numeric
                 (cond
                  ;; plus/minus explicitly means zero
                  ((or (string-match-p "\\+/-" clean)
                       (string-match-p "±" clean))
                   0)
                  ;; only pluses
                  ((string-match-p "^\\++$" clean)
                   (length clean))
                  ;; only minuses
                  ((string-match-p "^-+$" clean)
                   (- (length clean)))
                  ;; mixed or annotated (+ (?) etc.)
                  (t
                   (let* ((pluses (cl-count ?+ clean))
                          (minuses (cl-count ?- clean)))
                     (cond
                      ((> pluses minuses) pluses)
                      ((> minuses pluses) (- minuses))
                      (t 0)))))))
           (when (numberp numeric)
             (org-entry-put
              (point)
              "RATING"
              (number-to-string numeric)))))))
   t 'file))
