# Houri TODO

## Feature Ideas

- [x] **Yokai Backport**: Library sort option to ignore articles (A, An, The, etc.) — like J2K/Yokai. Example: "A Sign of Affection" sorts under S, "The Apothecary Diaries" sorts under A instead of T.
- [x] **Tracker**: MangaBaka (Mihon backport)
- [x] **Tracker**: Anime-Planet (scraping method, no api)
- [ ] **Tracker**: Hikka (Mihon backport)
- [ ] **Mihon Backport**: Reader
- [ ] **Mihon Backport**: Decoder (maybe not, highly experimental)
- [ ] **Mihon Backport**: Updater
- [ ] **New**: Toggle to enable an anime girl moaning whenever a chapter is completed. Moans should be randomized and have different probabilities of appearing (gacha mechanic) **Partially implemented, pending audio files.**
- [x] **E-Hentai**: Add content censorship status as a tag next to the content type tag (doujinshi, manga, etc). Content defaults to censored (no title marking), decensored (marked as [Decensored] in the title), or uncensored (marked as [Uncensored] in the title).
    - [x] Add accompanying sort/filter options for this change
- [ ] **E-Hentai**: Censorship type identification from manga tags (mosaic, full, etc)
- [ ] **New**: Add mango easter egg (?)

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
- [x] Fix extension crashes related to R8 stripping
- [x] Change about page's github URL to this fork repo
- [x] Fix "invalid_redirect" error with MangaBaka oAuth
- [x] Change AnimePlanet ID to something likely to be unique (ID 11 is hikka upstream)
- [x] Change AnimePlanet login to webview system to avoid cloudflare issues
- [x] Fix e/ex-hentai manga details page count overlaying on top of the content type chip instead of below the chips
- [x] Change MangaBaka to ID 11 (Hikka is ID 10)
- [x] Fix anime planet webview login - never gets session cookie once logged in
- [x] Remove komikku logo from more app locations
- [x] Remove all komikku references (filenames, WebDAV default, deep links, OAuth redirects)
- [ ] Fix database "export sensitive tokens" option not including exhentai auth cookies
- [ ] Fix e/exhentai censorship tags - censorship markers can appear at any order in the manga title, not just at the very end

## Chores
- [x] Replace all Komikku icons/branding with houri icons/branding
- [x] Update ancient test framework
- [x] Streamline gradle tasks
- [x] Update gradle plugins
- [ ] Modularize the codebase
- [x] Fix GitHub actions
- [x] Fine-tune GitHub actions (add compile, auto-release if version changed, etc)
- [ ] Replace Material UI framework with more stable library
