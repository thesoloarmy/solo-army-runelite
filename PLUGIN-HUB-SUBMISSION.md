# RuneLite Plugin Hub submission checklist

1. Create a **public** GitHub repository, recommended name: `solo-army-runelite`.
2. Put the contents of this folder at the repository root.
3. Test the plugin in RuneLite developer mode using Java 11 and the Gradle `run` task.
4. Confirm the README accurately documents every piece of data sent to `soloarmy.info`.
5. Commit and push the tested source to the public repository.
6. Copy the full 40-character commit SHA for the tested release commit.
7. Fork `https://github.com/runelite/plugin-hub` on GitHub.
8. Create a branch in your fork, e.g. `solo-army-bingo`.
9. Add a single marker file at `plugins/solo-army-bingo` containing:

       repository=https://github.com/YOUR-GITHUB-USER/solo-army-runelite.git
       commit=FULL_40_CHARACTER_COMMIT_SHA

10. Commit only that marker change to your plugin-hub fork.
11. Open a pull request from your fork/branch to `runelite/plugin-hub` `master`.
12. In the PR description, explain that the plugin:
    - tracks only loot matching the active Solo Army Bingo board;
    - communicates only with `https://soloarmy.info`;
    - sends the RSN during linking and relevant item ID/quantity/source/timestamp afterward;
    - embeds no shared API secret;
    - receives a per-installation scoped token after server-side WOM membership and Bingo signup checks.
13. Wait for Plugin Hub CI and review. Fix requested changes in the **same PR**, update your plugin repository, then update the marker's `commit=` SHA.

RuneLite reviews initial submissions and later updates for security and game-rule compliance. Plugins communicating with third-party servers need a warning explaining what data is sent; this project includes that disclosure in the config description and README.
