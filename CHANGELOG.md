# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] — 2026-10-05

First public release. A complete rewrite of the 1.x codebase, which could not be shipped: it ran database work on async threads and then touched the Bukkit API from them, so purchases silently took money without delivering items, and leaked locks eventually froze the economy.

### Added

- **Market** — GUI browser with category tabs, price-range filtering and name sorting; shulker-box preview; vanilla-translatable item names (custom anvil names preserved); configurable tax, listing fee, per-player cap, expiry and material blacklist; anti-typo price confirmation against recent average prices.
- **Buy orders** — post an order with funds escrowed; sellers deliver into an escrow "inspection" flow; the buyer accepts or declines from the mailbox; offers time out and return to the seller; early cancellation charges a fee.
- **Auctions** — bid escrow with immediate refunds to outbid players; buyout as a hard ceiling; anti-snipe extension; countdown and win broadcasts with clickable buttons; per-auction bid history.
- **Mailbox** — item, money and console-command mail (with `%player%` substitution); offline delivery; player-to-player sending with optional fee and tab completion; join notifications and old-mail reminders; claimed mail purged after N days, unclaimed mail never purged.
- **Admin tooling** — `mail` / `mailonline` / `mailall` with a confirmation GUI, batch undo, emergency refund, stuck-item redelivery and live statistics; a `tm_txlog` ledger for manual recovery.
- **Integrations** — Vault economy (required); LuckPerms group-based limits (optional); custom-item provider tagging for ItemsAdder, Oraxen, MMOItems and MythicMobs; per-world trade restrictions with subcommand allow-lists.
- **Internationalisation** — complete `zh_CN` and `en_US` language files, switchable at runtime, with a startup self-check that reports missing keys line by line.

### Architecture

- All business state changes are serialised on the main thread; no business locks exist.
- Two-phase commit for purchases, auction settlements and mail collection: guarded conditional `UPDATE` first, then money, then delivery, with full rollback on failure.
- SQLite (WAL, `synchronous=NORMAL`) written synchronously; in-memory maps are authoritative.
- Items serialised with Bukkit's object streams + Base64, preserving NBT; corrupt rows are quarantined rather than crashing a GUI.
- Zero shaded dependencies — the SQLite driver, Adventure and Vault are provided by the server at runtime.

### Known limitations

- SQLite single-file storage: no cross-server data sharing.
- Vault offers no transactions, so balance changes and database writes cannot be made atomic; a hard process kill leaves a small inconsistency window, arbitrated by `tm_txlog`.
- Custom items from plugins other than the four above are not explicitly identified (vanilla NBT serialisation usually carries them).
- Not Folia-compatible.
