package com.soloarmy.bingo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Cursor;
import javax.swing.BoxLayout;
import java.awt.Component;
import java.awt.Font;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.JProgressBar;
import javax.swing.Box;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;

/** Read-only, server-authoritative Bingo snapshot. All Swing mutations occur on the EDT. */
public class SoloArmyBingoPanel extends PluginPanel
{
    private static final int MAX_DIMENSION = 40;
    private static final int VIEW_COLS = 5;
    private final ItemManager itemManager;
    private final Consumer<String> bountyNotifier;
    private final Set<Integer> announcedBounties = new HashSet<>();
    private final Set<Integer> previousActiveBounties = new HashSet<>();
    private boolean bountyBaselineReady;
    private boolean disposed;
    private Timer iconRefreshTimer;
    private boolean refreshingSections;
    private final JLabel heading = new JLabel("Solo Army Bingo");
    private final JLabel status = new JLabel("Waiting for Bingo state...");
    private final JLabel eventTimer = new JLabel("");
    private final JComboBox<String> sections = new JComboBox<>();
    private final JPanel boardGrid = new JPanel();
    private final JPanel details = new JPanel();
    private final JPanel eligibleItems = new JPanel();
    private final JLabel tileSummary = new JLabel("Select a tile");
    private final JPanel pager = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 0));
    private final JPanel dropsList = verticalPanel();
    private final Map<Integer, JsonObject> tileById = new HashMap<>();
    private final Map<Integer, String> itemNames = new HashMap<>();
    private final JButton previous = new JButton("◀");
    private final JButton next = new JButton("▶");
    private final JLabel page = new JLabel("");
    private final JPanel standingsList = verticalPanel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JButton[] navigationButtons = new JButton[4];
    private final JScrollPane boardScroll;
    private final JLabel detailHeading = new JLabel("Select a tile");
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton eligibleToggle = new JButton("Eligible items ▾");
    private final JScrollPane eligibleScroll;
    private int selectedTileId = -1;
    private boolean eligibleExpanded = true;
    private int selectedTab = 0;
    private int myTeamId = -1;
    private final JPanel bountyList = verticalPanel();
    private JsonArray boards = new JsonArray();
    private JsonArray bounties = new JsonArray();
    private JsonObject competition;
    private final Map<Integer, String> tileNames = new HashMap<>();
    private int firstColumn;
    private int selectedSectionId = -1;
    private Instant serverTime;
    private Instant receivedAt;
    private Integer competitionId;
    private final Timer timer;

    public SoloArmyBingoPanel(Runnable refreshAction, ItemManager itemManager, Consumer<String> bountyNotifier)
    {
        super(false);
        this.itemManager = itemManager;
        this.bountyNotifier = bountyNotifier;
        setLayout(new BorderLayout(0, 5));
        JPanel top = new JPanel(new GridLayout(0, 1, 0, 3));
        for (JLabel label : new JLabel[] {heading, eventTimer, status})
        {
            label.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
            top.add(label);
        }
        JButton refresh = new JButton("Refresh from website");
        refresh.addActionListener(e -> refreshAction.run());
        top.add(refresh);
        add(top, BorderLayout.NORTH);

        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 11f));
        tabs.setBackground(new Color(38, 38, 38));
        tabs.setForeground(new Color(220, 220, 220));
        tabs.setBorder(BorderFactory.createEmptyBorder());
        // Hide native tab headers; use real buttons outside the tabbed pane.
        tabs.setUI(new BasicTabbedPaneUI()
        {
            @Override
            protected int calculateTabAreaHeight(int tabPlacement, int horizontalRunCount, int maxTabHeight)
            {
                return 0;
            }

            @Override
            protected int calculateTabAreaWidth(int tabPlacement, int verticalRunCount, int maxTabWidth)
            {
                return 0;
            }
        });
        tabs.setFocusable(false);
        JPanel board = new JPanel(new BorderLayout(0, 6));
        JPanel boardTop = new JPanel(new BorderLayout());
        boardTop.add(sections, BorderLayout.NORTH);
        pager.add(previous);
        pager.add(page);
        pager.add(next);
        boardTop.add(pager, BorderLayout.SOUTH);
        board.add(boardTop, BorderLayout.NORTH);
        JPanel boardContent = new JPanel(new BorderLayout(0, 6))
        {
            @Override
            public Dimension getPreferredSize()
            {
                Dimension preferred = super.getPreferredSize();
                // Tile details must never determine the scrollable board width.
                return new Dimension(215, preferred.height);
            }
        };
        // FlowLayout respects the grid's fixed preferred width; BorderLayout.NORTH
        // stretches it to the width requested by the selected tile details.
        JPanel fixedGridHolder = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        fixedGridHolder.add(boardGrid);
        JPanel boardStack = new JPanel();
        boardStack.setLayout(new BoxLayout(boardStack, BoxLayout.Y_AXIS));
        fixedGridHolder.setAlignmentX(Component.CENTER_ALIGNMENT);
        boardStack.add(fixedGridHolder);
        details.setLayout(new BorderLayout(0, 4));
        details.setBorder(BorderFactory.createEmptyBorder(8, 3, 3, 3));
        JPanel summary = new JPanel();
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        detailHeading.setFont(detailHeading.getFont().deriveFont(Font.BOLD, 13f));
        detailHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
        tileSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        progressBar.setPreferredSize(new Dimension(180, 7));
        progressBar.setStringPainted(false);
        progressBar.setForeground(new Color(75, 157, 94));
        summary.add(detailHeading);
        summary.add(Box.createVerticalStrut(4));
        summary.add(tileSummary);
        summary.add(Box.createVerticalStrut(5));
        summary.add(progressBar);
        summary.add(Box.createVerticalStrut(4));
        eligibleToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        eligibleToggle.setFocusable(false);
        eligibleToggle.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        eligibleScroll = new JScrollPane(eligibleItems);
        eligibleToggle.addActionListener(e -> {
            eligibleExpanded = !eligibleExpanded;
            eligibleScroll.setVisible(eligibleExpanded);
            eligibleToggle.setText(eligibleExpanded ? "Eligible items ▴" : "Eligible items ▾");
            details.revalidate();
        });
        summary.add(eligibleToggle);
        details.add(summary, BorderLayout.NORTH);
        eligibleItems.setLayout(new BoxLayout(eligibleItems, BoxLayout.Y_AXIS));
        eligibleScroll.setPreferredSize(new Dimension(200, 130));
        eligibleScroll.setBorder(BorderFactory.createEmptyBorder());
        details.add(eligibleScroll, BorderLayout.CENTER);
        details.setVisible(false);
        details.setAlignmentX(Component.CENTER_ALIGNMENT);
        details.setMaximumSize(new Dimension(215, 265));
        details.setPreferredSize(new Dimension(215, 265));
        boardStack.add(details);
        boardContent.add(boardStack, BorderLayout.NORTH);
        boardScroll = new JScrollPane(boardContent);
        boardScroll.setBorder(BorderFactory.createEmptyBorder());
        boardScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // Reserve scrollbar width even before tile details become scrollable.
        // Otherwise the centered grid jumps horizontally on selection.
        boardScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        board.add(boardScroll, BorderLayout.CENTER);
        tabs.addTab("Board", board);
        tabs.addTab("Bounties", new JScrollPane(bountyList));
        tabs.addTab("Standings", new JScrollPane(standingsList));
        tabs.addTab("Drops", new JScrollPane(dropsList));
        JPanel navigation = new JPanel(new GridLayout(2, 2, 8, 8));
        navigation.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        navigation.setBackground(new Color(31, 31, 31));
        // Visual order matches the user's preferred layout.
        int[] order = {0, 1, 2, 3};
        for (int index : order)
        {
            JButton button = new JButton(tabs.getTitleAt(index));
            navigationButtons[index] = button;
            button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));
            button.setFocusable(false);
            button.setOpaque(true);
            button.setContentAreaFilled(true);
            button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(76, 76, 76), 1),
                BorderFactory.createEmptyBorder(7, 3, 7, 3)));
            button.addActionListener(e -> tabs.setSelectedIndex(index));
            navigation.add(button);
        }
        JPanel contentWithNavigation = new JPanel(new BorderLayout());
        contentWithNavigation.add(navigation, BorderLayout.NORTH);
        contentWithNavigation.add(tabs, BorderLayout.CENTER);
        add(contentWithNavigation, BorderLayout.CENTER);
        updateTabButtons();
        tabs.addChangeListener(e -> {
            updateTabButtons();
            if (tabs.getSelectedIndex() != selectedTab)
            {
                selectedTab = tabs.getSelectedIndex();
                SwingUtilities.invokeLater(() -> {
                    Component view = tabs.getSelectedComponent();
                    if (view instanceof JScrollPane) ((JScrollPane) view).getVerticalScrollBar().setValue(0);
                    else if (view == board) boardScroll.getVerticalScrollBar().setValue(0);
                });
            }
        });

        sections.addActionListener(e -> {
            int index = sections.getSelectedIndex();
            if (index >= 0 && index < boards.size())
            {
                int id = integer(boards.get(index).getAsJsonObject(), "id", -1);
                if (id != selectedSectionId)
                {
                    firstColumn = 0;
                    selectedTileId = -1;
                    details.setVisible(false);
                    SwingUtilities.invokeLater(() -> boardScroll.getVerticalScrollBar().setValue(0));
                }
                selectedSectionId = id;
            }
            if (!refreshingSections) showSection();
        });
        previous.addActionListener(e -> { firstColumn = Math.max(0, firstColumn - VIEW_COLS); showSection(); });
        next.addActionListener(e -> { firstColumn += VIEW_COLS; showSection(); });
        timer = new Timer(1000, e -> updateTimers());
        timer.start();
    }

    public void dispose()
    {
        disposed = true;
        timer.stop();
        if (iconRefreshTimer != null) iconRefreshTimer.stop();
    }

    public void resetSession()
    {
        SwingUtilities.invokeLater(() -> {
            if (disposed) return;
            if (iconRefreshTimer != null) iconRefreshTimer.stop();
            bountyBaselineReady = false;
            announcedBounties.clear();
            previousActiveBounties.clear();
            competition = null;
            competitionId = null;
            serverTime = null;
            receivedAt = null;
            boards = new JsonArray();
            bounties = new JsonArray();
            tileNames.clear();
            sections.removeAllItems();
            boardGrid.removeAll();
            boardGrid.revalidate();
            boardGrid.repaint();
            details.setVisible(false);
            selectedTileId = -1;
            tileById.clear();
            status.setText("Waiting for login");
        });
    }

    public void showStatus(String message)
    {
        SwingUtilities.invokeLater(() -> { if (!disposed) status.setText(message); });
    }

    public void showState(JsonObject state)
    {
        SwingUtilities.invokeLater(() -> { if (!disposed) applyState(state); });
    }

    private void applyState(JsonObject state)
    {
        if (integer(state, "schema_version", -1) != 1)
        {
            status.setText("Unsupported state schema");
            return;
        }
        if (!bool(state, "active"))
        {
            if (iconRefreshTimer != null) iconRefreshTimer.stop();
            bountyBaselineReady = false;
            announcedBounties.clear();
            previousActiveBounties.clear();
            competition = null;
            competitionId = null;
            serverTime = null;
            receivedAt = null;
            boards = new JsonArray();
            bounties = new JsonArray();
            tileNames.clear();
            sections.removeAllItems();
            boardGrid.removeAll();
            boardGrid.revalidate();
            boardGrid.repaint();
            details.setVisible(false);
            selectedTileId = -1;
            tileById.clear();
            heading.setText("Solo Army Bingo");
            standingsList.removeAll();
            dropsList.removeAll();
            bountyList.removeAll();
            status.setText("Synced · No active Bingo");
            updateTimers();
            return;
        }
        competition = object(state, "competition");
        int newCompetitionId = integer(competition, "id", -1);
        if (competitionId == null || competitionId != newCompetitionId)
        {
            if (iconRefreshTimer != null) iconRefreshTimer.stop();
            bountyBaselineReady = false;
            announcedBounties.clear();
            previousActiveBounties.clear();
            selectedSectionId = -1;
            selectedTileId = -1;
            details.setVisible(false);
            firstColumn = 0;
        }
        competitionId = newCompetitionId;
        JsonObject team = object(state, "my_team");
        myTeamId = integer(team, "id", -1);
        heading.setText(shorten(string(competition, "name"), 26) + " · " + (team == null ? "No team" : shorten(string(team, "name"), 15)));
        serverTime = parseTime(string(state, "server_time"));
        receivedAt = Instant.now();
        boards = array(state, "sections");
        bounties = array(state, "bounties");
        tileNames.clear();
        tileById.clear();
        itemNames.clear();
        for (JsonElement board : boards)
        {
            for (JsonElement tile : array(board.getAsJsonObject(), "tiles"))
            {
                JsonObject t = tile.getAsJsonObject();
                tileNames.put(integer(t, "id", -1), string(t, "name"));
                tileById.put(integer(t, "id", -1), t);
                for (JsonElement item : array(t, "items"))
                {
                    JsonObject entry = item.getAsJsonObject();
                    itemNames.put(integer(entry, "item_id", -1), string(entry, "name"));
                }
            }
        }
        int oldId = selectedSectionId;
        refreshingSections = true;
        sections.removeAllItems();
        int selectedIndex = 0;
        for (int i = 0; i < boards.size(); i++)
        {
            JsonObject b = boards.get(i).getAsJsonObject();
            sections.addItem(string(b, "name"));
            if (integer(b, "id", -1) == oldId) selectedIndex = i;
        }
        if (sections.getItemCount() > 0)
        {
            sections.setSelectedIndex(selectedIndex);
            selectedSectionId = integer(boards.get(selectedIndex).getAsJsonObject(), "id", -1);
        }
        refreshingSections = false;
        showSection();
        if (selectedTileId >= 0 && tileById.containsKey(selectedTileId)) showTile(tileById.get(selectedTileId));
        showStandings(array(state, "teams"));
        showDrops(array(state, "recent_drops"));
        updateTimers();
        status.setText("Synced · " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(receivedAt));
    }

    private void updateTabButtons()
    {
        for (int i = 0; i < navigationButtons.length; i++)
        {
            JButton button = navigationButtons[i];
            if (button == null) continue;
            boolean active = i == tabs.getSelectedIndex();
            button.setBackground(active ? new Color(81, 81, 81) : new Color(48, 48, 48));
            button.setForeground(active ? Color.WHITE : new Color(218, 218, 218));
        }
    }

    private void showSection()
    {
        if (iconRefreshTimer != null) iconRefreshTimer.stop();
        Map<JButton, Integer> visibleItemIcons = new HashMap<>();
        boardGrid.removeAll();
        int index = sections.getSelectedIndex();
        if (index < 0 || index >= boards.size())
        {
            boardGrid.revalidate();
            boardGrid.repaint();
            return;
        }
        JsonObject section = boards.get(index).getAsJsonObject();
        int rows = Math.max(1, Math.min(MAX_DIMENSION, integer(section, "rows", 1)));
        int cols = Math.max(1, Math.min(MAX_DIMENSION, integer(section, "cols", 1)));
        firstColumn = Math.min(firstColumn, ((cols - 1) / VIEW_COLS) * VIEW_COLS);
        int visible = Math.min(VIEW_COLS, cols - firstColumn);
        page.setText("Cols " + (firstColumn + 1) + "–" + (firstColumn + visible) + "/" + cols);
        pager.setVisible(cols > VIEW_COLS);
        previous.setEnabled(firstColumn > 0);
        next.setEnabled(firstColumn + VIEW_COLS < cols);
        // Keep the board within RuneLite's narrow sidebar, including the vertical
        // scrollbar width. A selected tile must not change these dimensions.
        final int tileSize = visible <= 3 ? 66 : visible == 4 ? 49 : 39;
        final int tileGap = 3;
        boardGrid.setLayout(new GridLayout(rows, visible, tileGap, tileGap));
        Dimension gridSize = new Dimension(visible * tileSize + Math.max(0, visible - 1) * tileGap,
            rows * tileSize + Math.max(0, rows - 1) * tileGap);
        boardGrid.setPreferredSize(gridSize);
        boardGrid.setMinimumSize(gridSize);
        boardGrid.setMaximumSize(gridSize);
        Map<Integer, JsonObject> tiles = new HashMap<>();
        for (JsonElement element : array(section, "tiles"))
        {
            JsonObject tile = element.getAsJsonObject();
            int row = integer(tile, "row", -1), col = integer(tile, "col", -1);
            if (row >= 0 && row < rows && col >= 0 && col < cols) tiles.put(row * cols + col, tile);
        }
        for (int row = 0; row < rows; row++)
        {
            for (int col = firstColumn; col < firstColumn + visible; col++)
            {
                JsonObject tile = tiles.get(row * cols + col);
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(tileSize, tileSize));
                button.setFocusable(false);
                button.setMargin(new java.awt.Insets(1, 1, 1, 1));
                if (tile == null)
                {
                    button.setEnabled(false);
                    boardGrid.add(button);
                    continue;
                }
                int progress = integer(tile, "my_team_progress", 0);
                int required = Math.max(1, integer(tile, "required_count", 1));
                // Progress and bounty are independent visual signals. The requirement
                // is met at required_count; green is reserved for a reached cap.
                int cap = integer(tile, "max_claims", 0);
                boolean capped = cap > 0 && progress >= cap;
                boolean complete = progress >= required;
                boolean bounty = activeBounty(integer(tile, "id", -1)) != null;
                int itemId = representativeItem(tile);
                if (itemId > 0)
                {
                    try { button.setIcon(new ImageIcon(itemManager.getImage(itemId)));
                        visibleItemIcons.put(button, itemId); }
                    catch (RuntimeException ignored) { button.setText(String.valueOf(row * cols + col + 1)); }
                }
                else button.setText(String.valueOf(row * cols + col + 1));
                button.setBackground(capped ? new Color(46, 108, 68)
                    : complete ? new Color(145, 104, 38) : new Color(55, 55, 55));
                // A gold outline marks a live bounty without obscuring progress.
                button.setBorder(bounty
                    ? BorderFactory.createLineBorder(new Color(218, 165, 55), 2)
                    : BorderFactory.createLineBorder(new Color(65, 65, 65), 1));
                button.setOpaque(true);
                button.setToolTipText(string(tile, "name") + " · " + progress + "/" + required + (bounty ? " · BOUNTY" : ""));
                button.addActionListener(e -> showTile(tile));
                // Native button selection/focus feedback is enough; do not add a
                // delayed blue outline when the tile details are refreshed.
                boardGrid.add(button);
            }
        }
        boardGrid.revalidate();
        boardGrid.repaint();
        // RuneLite item sprites can become available after the first board paint.
        // Re-request icons for this specific set of buttons for a short bounded period.
        // All Swing updates run on the EDT; stop on board switch or plugin disposal.
        if (!visibleItemIcons.isEmpty())
        {
            final int[] attempts = {0};
            iconRefreshTimer = new Timer(500, e -> {
                if (disposed || ++attempts[0] >= 12)
                {
                    ((Timer) e.getSource()).stop();
                    return;
                }
                for (Map.Entry<JButton, Integer> entry : visibleItemIcons.entrySet())
                {
                    JButton button = entry.getKey();
                    if (button.getParent() != boardGrid) continue;
                    try
                    {
                        java.awt.image.BufferedImage image = itemManager.getImage(entry.getValue());
                        if (image != null)
                        {
                            button.setIcon(new ImageIcon(image));
                            button.repaint();
                        }
                    }
                    catch (RuntimeException ignored)
                    {
                        // Item definitions may not yet be ready.
                    }
                }
            });
            iconRefreshTimer.start();
        }
    }

    private void showTile(JsonObject tile)
    {
        selectedTileId = integer(tile, "id", -1);
        detailHeading.setText(shorten(string(tile, "name"), 30));
        int completed = Math.max(0, integer(tile, "my_team_progress", 0));
        int required = Math.max(1, integer(tile, "required_count", 1));
        progressBar.setMaximum(required);
        progressBar.setValue(Math.min(required, completed));
        StringBuilder sb = new StringBuilder("<html>");
        sb.append("Progress: ").append(integer(tile, "my_team_progress", 0)).append("/").append(integer(tile, "required_count", 1));
        sb.append(" · ").append(integer(tile, "points", 0)).append(" pts/item");
        if (tile.has("max_claims") && !tile.get("max_claims").isJsonNull()) sb.append("<br>Contribution cap: ").append(integer(tile, "max_claims", 0));
        JsonObject bounty = activeBounty(integer(tile, "id", -1));
        if (bounty != null) sb.append(" · Bounty ").append(formatMultiplier(bounty)).append("×");
        // A fixed text width prevents a long bounty/detail line from expanding the board grid.
        tileSummary.setText(sb.append("</html>").toString());
        tileSummary.setPreferredSize(new Dimension(195, 40));
        tileSummary.setMaximumSize(new Dimension(195, 40));
        eligibleItems.removeAll();
        JsonArray items = array(tile, "items");
        for (JsonElement element : items)
        {
            JsonObject item = element.getAsJsonObject();
            eligibleItems.add(itemRow(integer(item, "item_id", -1), string(item, "name"), ""));
        }
        if (items.size() == 0) eligibleItems.add(new JLabel("No eligible items listed"));
        eligibleToggle.setText("Eligible items (" + items.size() + ") " + (eligibleExpanded ? "▴" : "▾"));
        eligibleScroll.setVisible(eligibleExpanded);
        details.setVisible(true);
        eligibleItems.revalidate();
        eligibleItems.repaint();
        details.revalidate();
        boardScroll.revalidate();
        boardGrid.repaint();
    }

    private JPanel itemRow(int itemId, String title, String subtitle)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBorder(BorderFactory.createEmptyBorder(5, 4, 5, 4));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        if (itemId > 0)
        {
            try { row.add(new JLabel(new ImageIcon(itemManager.getImage(itemId))), BorderLayout.WEST); }
            catch (RuntimeException ignored) { /* Unknown item IDs should not break the panel. */ }
        }
        JLabel text = new JLabel("<html><b>" + escape(shorten(title, 34)) + "</b>"
            + (subtitle.isEmpty() ? "" : "<br><font color='#AAAAAA'>" + escape(subtitle) + "</font>") + "</html>");
        row.add(text, BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private static JPanel verticalPanel()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private void showStandings(JsonArray teams)
    {
        standingsList.removeAll();
        standingsList.add(sectionTitle("TEAM STANDINGS · " + teams.size() + " teams"));
        int rank = 0;
        for (JsonElement element : teams)
        {
            JsonObject t = element.getAsJsonObject();
            int teamId = integer(t, "id", -1);
            boolean own = teamId >= 0 && teamId == myTeamId;
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 3, 0, getBackground()),
                BorderFactory.createEmptyBorder(10, 7, 10, 7)));
            row.setBackground(own ? new Color(70, 58, 36) : new Color(48, 48, 48));
            row.setOpaque(true);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
            JLabel medal = new JLabel(rank == 0 ? "★" : "#" + (rank + 1));
            medal.setForeground(rank == 0 ? new Color(239, 183, 73) : Color.LIGHT_GRAY);
            row.add(medal, BorderLayout.WEST);
            JLabel name = new JLabel("<html><b>" + escape(shorten(string(t, "name"), 19)) + "</b>"
                + (own ? "<br><font color='#EAC47A'>Your team</font>" : "") + "</html>");
            row.add(name, BorderLayout.CENTER);
            JLabel points = new JLabel("<html><b>" + escape(string(t, "points")) + "</b><br><font color='#AAAAAA'>pts</font></html>");
            row.add(points, BorderLayout.EAST);
            standingsList.add(row);
            rank++;
        }
        if (rank == 0) standingsList.add(new JLabel("No standings yet"));
        standingsList.revalidate();
        standingsList.repaint();
    }

    private static JLabel sectionTitle(String title)
    {
        JLabel label = new JLabel(title);
        label.setForeground(new Color(212, 174, 103));
        label.setBorder(BorderFactory.createEmptyBorder(10, 5, 9, 3));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private void showDrops(JsonArray claims)
    {
        dropsList.removeAll();
        dropsList.add(sectionTitle("RECENT ACCEPTED DROPS"));
        for (JsonElement element : claims)
        {
            JsonObject d = element.getAsJsonObject();
            int itemId = integer(d, "item_id", -1);
            String itemName = itemNames.getOrDefault(itemId, tileNames.getOrDefault(integer(d, "tile_id", -1), "Item"));
            String info = string(d, "player") + " · Qty " + integer(d, "quantity", 0) + " · +" + string(d, "points") + " pts";
            dropsList.add(styledRow(itemId, itemName, info, false));
        }
        if (claims.size() == 0) dropsList.add(new JLabel("No accepted drops yet"));
        dropsList.revalidate();
        dropsList.repaint();
    }

    private void updateTimers()
    {
        Instant now = serverTime != null && receivedAt != null ? serverTime.plus(Duration.between(receivedAt, Instant.now())) : Instant.now();
        if (receivedAt != null && Duration.between(receivedAt, Instant.now()).toMinutes() >= 3
            && status.getText().startsWith("Synced"))
        {
            status.setText("Stale data · last synced " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(receivedAt));
        }
        Instant end = parseTime(string(competition, "end_at"));
        Instant startEvent = parseTime(string(competition, "start_at"));
        eventTimer.setText(startEvent != null && startEvent.isAfter(now)
            ? "Event starts in: " + countdown(startEvent, now)
            : end == null ? "" : end.isAfter(now) ? "Event ends in: " + countdown(end, now) : "Event ended");
        bountyList.removeAll();
        bountyList.add(sectionTitle("BINGO BOUNTIES"));
        Set<Integer> priorActive = new HashSet<>(previousActiveBounties);
        Set<Integer> activeNow = new HashSet<>();
        for (JsonElement element : bounties)
        {
            JsonObject bounty = element.getAsJsonObject();
            Instant start = parseTime(string(bounty, "start_at"));
            Instant finish = parseTime(string(bounty, "end_at"));
            if (start == null || finish == null || !finish.isAfter(now)) continue;
            boolean active = !start.isAfter(now);
            int bountyId = integer(bounty, "id", -1);
            if (active && bountyId >= 0)
            {
                activeNow.add(bountyId);
                if (bountyBaselineReady && !previousActiveBounties.contains(bountyId) && announcedBounties.add(bountyId))
                {
                    bountyNotifier.accept("Solo Army Bingo: " + formatMultiplier(bounty) + "× bounty started: "
                        + shorten(tileNames.getOrDefault(integer(bounty, "tile_id", -1), "Tile"), 60));
                }
            }
            String label = (active ? "ACTIVE " : "UPCOMING ") + formatMultiplier(bounty) + "× · "
                + tileNames.getOrDefault(integer(bounty, "tile_id", -1), "Tile")
                + " · " + countdown(active ? finish : start, now);
            int tileId = integer(bounty, "tile_id", -1);
            JsonObject tile = tileById.get(tileId);
            int iconId = tile == null ? -1 : representativeItem(tile);
            JPanel item = styledRow(iconId, tileNames.getOrDefault(tileId, "Tile"),
                (active ? "ACTIVE · Ends in " : "UPCOMING · Starts in ")
                + countdown(active ? finish : start, now), active);
            JLabel multiplierLine = new JLabel("  MULTIPLIER  " + formatMultiplier(bounty) + "×");
            multiplierLine.setForeground(new Color(239, 188, 83));
            multiplierLine.setFont(multiplierLine.getFont().deriveFont(Font.BOLD, 14f));
            item.add(multiplierLine, BorderLayout.SOUTH);
            item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 88));
            item.setToolTipText(label);
            if (tile != null)
            {
                item.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
                item.addMouseListener(new java.awt.event.MouseAdapter()
                {
                    @Override public void mouseClicked(java.awt.event.MouseEvent e) { jumpToTile(tileId); }
                });
            }
            bountyList.add(item);
        }
        if (!bountyBaselineReady && competition != null)
        {
            // Bounties already active at initial sync are not new announcements.
            announcedBounties.addAll(activeNow);
            bountyBaselineReady = true;
        }
        previousActiveBounties.clear();
        previousActiveBounties.addAll(activeNow);
        boolean changed = !priorActive.equals(activeNow);
        if (changed && sections.getSelectedIndex() >= 0) showSection();
        if (bountyList.getComponentCount() == 1) bountyList.add(new JLabel("No active or upcoming bounties"));
        bountyList.revalidate();
        bountyList.repaint();
    }

    private JPanel styledRow(int itemId, String name, String info, boolean highlighted)
    {
        JPanel row = itemRow(itemId, name, info);
        row.setOpaque(true);
        row.setBackground(highlighted ? new Color(67, 54, 32) : new Color(48, 48, 48));
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 3, 0, getBackground()),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 69));
        return row;
    }

    private void jumpToTile(int tileId)
    {
        for (int i = 0; i < boards.size(); i++)
        {
            JsonObject section = boards.get(i).getAsJsonObject();
            for (JsonElement entry : array(section, "tiles"))
            {
                JsonObject tile = entry.getAsJsonObject();
                if (integer(tile, "id", -1) != tileId) continue;
                tabs.setSelectedIndex(0);
                sections.setSelectedIndex(i);
                firstColumn = Math.max(0, integer(tile, "col", 0) / VIEW_COLS * VIEW_COLS);
                showSection();
                showTile(tile);
                SwingUtilities.invokeLater(() -> boardScroll.getVerticalScrollBar().setValue(0));
                return;
            }
        }
    }

    private JsonObject activeBounty(int tileId)
    {
        Instant now = serverTime != null && receivedAt != null ? serverTime.plus(Duration.between(receivedAt, Instant.now())) : Instant.now();
        for (JsonElement element : bounties)
        {
            JsonObject b = element.getAsJsonObject();
            Instant start = parseTime(string(b, "start_at")), end = parseTime(string(b, "end_at"));
            if (integer(b, "tile_id", -1) == tileId && start != null && end != null && !start.isAfter(now) && end.isAfter(now)) return b;
        }
        return null;
    }

    private static int representativeItem(JsonObject tile)
    {
        for (JsonElement element : array(tile, "items"))
        {
            int id = integer(element.getAsJsonObject(), "item_id", 0);
            if (id > 0) return id;
        }
        return 0;
    }

    private static String formatMultiplier(JsonObject bounty)
    {
        try { return new java.math.BigDecimal(string(bounty, "multiplier")).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException e) { return "1"; }
    }

    private static String countdown(Instant target, Instant now)
    {
        long seconds = Math.max(0, Duration.between(now, target).getSeconds());
        long days = seconds / 86400, hours = seconds % 86400 / 3600, mins = seconds % 3600 / 60, secs = seconds % 60;
        return days > 0 ? days + "d " + hours + "h " + mins + "m" : String.format("%02d:%02d:%02d", hours, mins, secs);
    }

    private static Instant parseTime(String value)
    {
        try { return value.isEmpty() ? null : OffsetDateTime.parse(value).toInstant(); }
        catch (RuntimeException e) { return null; }
    }

    private static JsonObject object(JsonObject obj, String key)
    {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject obj, String key)
    {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static String string(JsonObject obj, String key)
    {
        try { return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : ""; }
        catch (RuntimeException e) { return ""; }
    }

    private static int integer(JsonObject obj, String key, int fallback)
    {
        try { return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback; }
        catch (RuntimeException e) { return fallback; }
    }

    private static boolean bool(JsonObject obj, String key)
    {
        try { return obj != null && obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).getAsBoolean(); }
        catch (RuntimeException e) { return false; }
    }

    private static String shorten(String value, int length)
    {
        return value.length() <= length ? value : value.substring(0, length - 1) + "…";
    }

    private static String escape(String value)
    {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static JTextArea textArea()
    {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }
}
