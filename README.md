# Solo Army Bingo — RuneLite Plugin

Automatically submits only loot that matches the currently active Solo Army Bingo board to `https://soloarmy.info`.

## Privacy / network behavior

When automatic tracking is enabled, the plugin sends the logged-in RuneScape name while linking and sends only relevant Bingo loot metadata: item ID, quantity, loot source/type, timestamp, a random event ID, and plugin version. It does not send unrelated loot. The Solo Army server independently checks current WOM clan membership, active Bingo signup, the active board, duplicates, and player/team mapping.

The server issues a per-installation bearer token after eligibility checks. No global API secret is embedded in the plugin. Tokens are scoped to RuneLite Bingo endpoints and can be revoked server-side.

## User setup

1. Install **Solo Army Bingo** from the RuneLite Plugin Hub.
2. Log into the RuneScape account that is currently in Solo Army and signed up for the active Bingo.
3. Enable **Automatic Bingo tracking** in the plugin settings.
4. The plugin links automatically and downloads only the item IDs on the active Bingo board.
5. Matching drops are submitted automatically. Unrelated drops stay on the user's computer.

## Plugin Hub submission

See `PLUGIN-HUB-SUBMISSION.md` for the exact submission checklist.

