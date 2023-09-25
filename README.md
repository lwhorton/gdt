# some thoughts on the matter

objective: build a small web app that queries the giantbomb API to offer users
the ability to
1. search for video games
2. add those video games to a cart
3. checkout with those games in the cart

## objectives
- keep the whole thing as dependency-free as possible
- "use the platform" - html, css, and barely any js
- provide useful hypermedia responses wherever possible. don't write RPC.
- don't spend much time polishing a toy- this is only a proof of some level of
  competency
- dont run out of free rate-limited API requests

## notes
- we're using core.memoize on a 12h ttl, which seems reasonable for essentially
  a user-fed video game wiki
- our single user is hardcoded "user-1", and no authn/authz or sessions were
  considered
- no database was configured, we're using an atom with useful domain functions
  covering mutation
- checkouts can be exceedingly complex on large distributed, eventually
  consistent systems. we are making 0 attempt at solving such problems as
  inventory mutexes in order to finish in a reasonable amount of time.
- in a similar line of reasoning, we're omitting meaningful transactionality
- in a similar line of reasoning, the layout is functional but not pretty
- a judicous use of 303s keeps us safe/idempotent in the absence of a
  transactionality system
- due to the slowness of the provided search API, we've implemented some
  progressive enhancement to provide more meaningful feedback to the user while
  they wait. this comes at the cost of some complexity, and i don't really like
  it.

# getting started

## to dev

```
GIANTBOMB_API_KEY=... make dev

;; repl in, and eval:
(gdt.main/-main ...relevant args...)

;;
visit localhost:3000
```
