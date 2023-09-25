dev:
	clj -M:nrepl:portal -m nrepl.cmdline --middleware '[portal.nrepl/wrap-portal]'
.PHONY: dev
