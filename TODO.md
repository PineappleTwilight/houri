# Houri TODO

## Feature Ideas

- [x] Library sort option to ignore articles (A, An, The, etc.) — like J2K/Yokai. Example: "A Sign of Affection" sorts under S, "The Apothecary Diaries" sorts under A instead of T.
- [ ] Port mihon's new decoder to the app (maybe not, highly experimental)
- [x] Add mangabaka tracker (port from mihon)
- [ ] Add animeplanet tracker (no api)
- [ ] Port mihon's new reader
- [~] Toggle to enable an anime girl moaning whenever a chapter is completed. Moans should be randomized and have different probabilities of appearing (gacha mechanic)
- [ ] Add content censorship status as a tag next to the content type tag (doujinshi, manga, etc) in e/exhentai. Content defaults to censored (no title marking), decensored (marked as [Decensored] in the title), or uncensored (marked as [Uncensored] in the title).
    - [ ] Add accompanying sort/filter options for this change

## Bugfixes
- [x] Fix UI transition choppiness.
- [x] Turn off "Recommendations" by default.
- [x] Add caching to recommendations (db)
- [x] Evaluate and bugfix all delegated sources (possibly removing some)
- [x] Refresh manga details page when migrated (prevents having to exit the manga and view the details again for the changes to apply)
- [x] Improve Discord connection feature
- [x] Enable EH functionality by default.
- [x] Refresh library on title clean
- [x] Fix some extensions not registering
- [ ] Fix mass migration crashes (needs more investigation to determine if this was one-off)
- [x] Change about page's github URL to this fork repo

## Chores
- [x] Replace all Komikku icons/branding with pineapple icons/branding
- [x] Update ancient test framework
- [ ] Streamline gradle tasks
- [ ] Update gradle plugins
- [ ] Modularize the codebase
- [x] Fix GitHub actions
- [x] Fine-tune GitHub actions (add compile, auto-release if version changed, etc)
- [ ] Replace Material UI framework with more stable library
