# What is this?

objective: build a small web app that queries the giantbomb API to offer users
the ability to:
1. search for video games
2. add those video games to a cart
3. checkout with those games in the cart

## objectives
- use clojure and its web idioms
- as few dependencies as reasonable
- "use the platform" with html, css, and (hopefully barely any) js. let's not
  use some giant rails/django/phx-like thing.
- provide useful hypermedia responses wherever possible. http is great, don't
  write RPC.
- don't spend time polishing a toy- this is only a proof of some level of
  competency
- dont run out of free rate-limited API requests

## notes
- we're using core.memoize on a 12h ttl, which seems reasonable for what is
  essentially a user-fed video game wiki-over-API. this caching could cause some
  weird unexpected behaviors, which we're not going to solve in this small
  project.
- our single user is hardcoded "user-1", and no authn/authz or sessions were
  considered
- there is no database, we're using an atom with useful domain functions
  covering mutation. a db could be swapped in later by only changing these
  domain functions, if necessary.
- checkouts can be exceedingly complex on distributed, eventually
  consistent systems. we are making 0 attempt at solving these problems, such as
  inventory mutexes, in order to finish in a reasonable amount of time.
- in a similar line of reasoning, we're omitting transactionality/rollback
- in respect to time, the layout is functional but not pretty
- we leverage http and a lot of 303s to remain safe/idempotent in the absence of
  a transactionality system
- due to the slowness of the provided search API, we've implemented some
  progressive enhancement to provide more meaningful feedback to the user while
  they wait. this comes at the cost of some complexity, and i don't really like
  it, but it's quick-enough.
- related to the slowness of the API in general, we're both caching responses
  and capturing entities

# getting started

there is currently no build/deploy implemented here. we could package a jar with
tools.deps, but we are short on time.

## development

```
$> GIANTBOMB_API_KEY=... make dev

;; connect to the repl, and eval:
repl> (gdt.main/-main)

;; browser
url: localhost:3000
```

please let me know (@lwhorton) if anything seems broken.
