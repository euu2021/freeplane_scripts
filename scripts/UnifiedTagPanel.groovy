// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 2 of the License, or
// (at your option) any later version.
// Version: 1.0.0

/***************************************************************************

 Unified Tag Panel — "one tag panel to rule them all".

 Discussion threads: https://github.com/freeplane/freeplane/discussions/2953 (announcement) · https://github.com/freeplane/freeplane/issues/2926 · https://github.com/freeplane/freeplane/discussions/2257

 One overlay panel that unifies Freeplane's three tag interfaces:
   1. the Tags side panel (view + filter + assign),
   2. the "Edit node tags" dialog (keyboard-first assign/create on the selected nodes),
   3. the "Manage tag categories" dialog (rename / move / delete / color the hierarchy).

 Everything goes through the PUBLIC scripting API:
   - reading:   mindMap.tagCategories.read()  (tree, colors, separator, revision)
   - node tags: node.tags.add/remove          (registers unknown tags on the map)
   - structure: mindMap.tagCategories.edit(MapTagCategoryInstructionRequest)
                (each edit = one undo step; node tags are rewritten along)

 The panel is an overlay on the map (like SearchPanel.groovy), anchored to the
 TOP-RIGHT corner of the tab — SearchPanel owns the top-left. By default it attaches to
 the tab it was launched on and stays there; turn on "Show on every tab" in Options… and
 the SAME panel moves to whatever tab becomes active, so it is always on screen.

 Usage:
   - launch the script       -> TOGGLES the panel on the current map: opens it (with the
                                focus already in the filter field), or hides it if it is
                                open. Hiding remembers the expansion, the filter text and
                                the wide/edit modes, so showing it again resumes where you
                                were. Bind it to a shortcut to flick the panel in and out.
   - type in the field       -> live search, accent-insensitive, with the typed text
                                HIGHLIGHTED inside every row that carries it
   - ▼ / ▽ next to the field -> the two search modes of issue #2926: ▼ filters (the tags
                                that do not match are hidden, matches auto-expand) and ▽
                                only highlights (nothing is hidden, the structure and the
                                scroll position stay put, and just the paths leading to a
                                match are opened). ▼ is the default.
   - ENTER in the field      -> assigns the best match to the selected node(s);
                                if nothing matches, CREATES the typed tag (use :: for
                                categories) and assigns it — the Edit-Tags workflow
   - Ctrl+ENTER              -> always creates the typed tag, even if something matches
   - ↑ / ↓ in the field      -> walk the tag-tree selection without leaving the field,
                                stopping ONLY on tags that match what was typed: the
                                ancestor categories drawn above a nested match are skipped.
                                Under a filter the selection starts on the first match, so
                                what ENTER will assign is always visible.
   - click a tag             -> toggles it on the selected node(s)  [✓ = on all,
                                ◐ = on some]. Clicks on the expand handle still fold.
   - favorites strip         -> the row of chips under the filter field: the tags pinned
                                in THIS map. Click a chip to toggle it on the selected
                                node(s); right-click for assign/remove, reorder, show in
                                the tree, or unpin. Pin a tag from its context menu in the
                                tree (★), or by dragging it onto the strip in edit mode.
                                Favorites are stored IN THE MAP FILE (mindMap.storage),
                                the same place and lifecycle as the tags themselves, and
                                they follow the tags through renames and moves made here.
   - ✎ on the title bar      -> EDIT MODE: clicks only select (no toggling), so the
                                hierarchy can be reorganized without side effects; and
                                DRAG & DROP reorganizes the tree — drop ON a tag to nest
                                under it, BETWEEN tags to position among siblings, on the
                                uncategorized bucket to uncategorize a leaf, or drag an
                                uncategorized tag into the tree to categorize it
                                (drag is disabled while a filter is active)
   - Alt+↑/↓                 -> move tag among siblings   (field or tree focused)
   - Alt+←                   -> promote (become sibling of its parent)
   - Alt+→                   -> demote (become child of the previous sibling)
   - F2                      -> rename inline (tree focused)
   - Insert                  -> add child tag (tree focused)
   - Delete pressed twice    -> delete the tag from the map (the key arms on the first
                                press so a stray Delete costs nothing; the context menu's
                                Delete acts at once — picking it is already deliberate) (tree focused)
   - Sort by usage           -> a second ordering, off by default (the normal one is the
                                map's own tag tree). Turned on from the context menu, the
                                category NESTING disappears: every tag becomes one row,
                                labelled with its qualified name, most used first. Handy to
                                see what you actually use; reordering (Alt+arrows, drag) is
                                off while it is on, since the rows are no longer siblings.
   - usage counts            -> each tag shows how many nodes carry it: "urgent (5)".
                                A category with subtags shows "own/whole category" —
                                "work (2/17)". A tag nobody uses shows (0) and is painted
                                faded, so it is safe to prune. The counts update live.
   - right-click             -> context menu: assign/remove, favorites, rename, add child,
                                delete, set/reset color, move ops, filter the MAP
                                by the tag (with folding restored on clear), the
                                usage commands "Hide unused tags" / "Delete all unused
                                tags", and the "Close after insert" option
   - Show on every tab       -> OFF by default. On, the panel moves to the tab you switch
                                to (one panel, one state — not a copy per tab), reloading
                                the tags and the favorites of the map it lands on.
   - Close after insert      -> ON by default: assigning a tag hides the panel and hands
                                the focus back to the map, so the whole gesture is
                                trigger → type → ENTER. Removing a tag never closes it.
                                It is a PROFILE preference (it has to outlive the panel it
                                closes), shared by every map, and it is remembered.
   - « on the title bar      -> pin the panel wide, ignoring hover (it grows leftwards,
                                since the panel hangs on the right edge); » restores it
   - ✕ / ESC                 -> close (also clears the map filter it applied)

 *****************************************************************/

import groovy.transform.Field

import org.freeplane.api.MapTagCategoryInstruction
import org.freeplane.api.MapTagCategoryInstructionRequest
import org.freeplane.api.MapTagCategoryInstructionType
import org.freeplane.api.MapTagTargetLocation
import org.freeplane.core.resources.ResourceController
import org.freeplane.core.ui.components.UITools
import org.freeplane.core.util.HtmlUtils
import org.freeplane.features.filter.Filter
import org.freeplane.features.filter.FilterController
import org.freeplane.features.filter.condition.ICondition
import org.freeplane.features.icon.Tag
import org.freeplane.features.icon.TagCategories
import org.freeplane.features.icon.Tags as CoreTags
import org.freeplane.features.map.IMapSelection
import org.freeplane.features.map.IMapChangeListener
import org.freeplane.features.map.INodeChangeListener
import org.freeplane.features.map.INodeSelectionListener
import org.freeplane.features.map.MapChangeEvent
import org.freeplane.features.map.MapModel
import org.freeplane.features.map.NodeChangeEvent
import org.freeplane.features.map.NodeDeletionEvent
import org.freeplane.features.map.NodeModel
import org.freeplane.features.icon.IconController
import org.freeplane.features.mode.Controller
import org.freeplane.features.ui.IMapViewChangeListener
import org.freeplane.plugin.script.proxy.ProxyFactory
import org.freeplane.view.swing.map.MapView
import org.freeplane.view.swing.map.MapViewScrollPane

import javax.swing.*
import javax.swing.event.CellEditorListener
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.*

// after java.awt.*: without this, List becomes java.awt.List, which is not generic
import java.util.List


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ User settings ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

@Field String panelTextFontName = "Dialog"
@Field int panelTextFontSize = 14

@Field int retractedWidthFactor = 20
@Field int expandedWidthFactor = 4
@Field int wideWidthPercent = 40

@Field int retractDelayMs = 400
@Field int refreshCoalesceMs = 150
// Rebuilding the tree costs ~1 ms per row (MEASURED: ~9 ms for 9 rows, ~45 ms for 54),
// so rebuilding on every keystroke makes typing lag on a map with many tags. This waits
// for a short pause instead. ENTER never waits — it flushes the pending rebuild first.
@Field int filterDebounceMs = 120

// expand/retract transition, ease-out; 0 or 1 = no animation. Skipped above the row cap.
@Field int resizeAnimationSteps = 4
@Field int resizeAnimationStepMs = 15
@Field int resizeAnimationMaxRows = 80

@Field int titleBarHeight = 24
@Field String titleBarText = "Tags"

// Usage count next to each tag (issue #2948): "urgent (5)". A category that has subtags
// shows "own/whole category" — "work (2/17)" = 2 nodes tagged exactly work, 17 nodes in
// the category counting the subtags. A tag nobody uses shows (0) and is painted faded.
@Field boolean showUsageCounts = true
@Field boolean showCategoryTotals = true
// faded look of an unused tag: how far its chip color is pulled toward the map background
@Field float unusedTagFadeRatio = 0.72f

// Highlight of the typed text inside each row (#2926). Amber with black text: the chip
// underneath can be any color, so the pair has to carry its own contrast.
@Field String matchHighlightHex = "#ffd54f"
// the toggle next to the filter field: filled = hiding what does not match, hollow = only
// highlighting
@Field String filterHidesSymbol = "▼"
@Field String highlightOnlySymbol = "▽"

// favorites strip (below the filter field): gaps between chips and around the rows
@Field int favoritesGapX = 4
@Field int favoritesGapY = 3
// the panel is anchored to the RIGHT edge, so it grows LEFTWARDS: « widens, » restores
// (mirror image of a left-anchored panel like SearchPanel)
@Field String wideOffSymbol = "«"
@Field String wideOnSymbol = "»"
@Field String closeButtonSymbol = "✕"
@Field String clearButtonSymbol = "⌫"
@Field int widthOfTheClearButton = 30

@Field String filterFieldPlaceholder = "Filter or create…"

// opaque background of the bars (see SearchPanel for the forbidden gray band)
@Field Color barColor = new Color(0x50, 0x50, 0x50)
// edit-mode button background while the mode is ON
@Field Color editModeOnColor = new Color(0xc6, 0x8a, 0x00)

@Field int panelBorderThickness = 1
@Field int panelBorderOpacity = 150

// what the map filter keeps visible besides the tagged nodes themselves
@Field boolean showTagFilterDescendants = false

// delete needs a second press within this window
@Field int deleteArmMs = 4000

// "Close after insert": hide the panel as soon as a tag is assigned, so trigger → type →
// ENTER ends back in the map with no extra gesture. Toggled from the context menu.
// Default ON. Removing a tag never closes — the option is about INSERTING.
@Field boolean closeAfterInsertDefault = true

// Colour given to a tag CREATED IN THIS PANEL (issue #2950), chosen in Options…:
//   "default" — Freeplane decides, deriving the colour from the name (what it has always
//               done here; kept as the factory setting so nothing changes unasked)
//   "inherit" — take the colour of the nearest ancestor category that already exists
//   "fixed"   — always the colour picked in the dialog
@Field String newTagColorModeDefault = "default"
// only used by "fixed", and as the last resort of "inherit" when a colour was ever picked
@Field String newTagColorFallback = "#3366cc"

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ User settings ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


@Field final String PANEL_NAME = "UnifiedTagPanel"
@Field final String FILTER_FIELD_NAME = "UnifiedTagPanelField"
@Field final String FAVORITES_STRIP_NAME = "UnifiedTagPanelFavorites"
// Favorites live in the MAP, exactly like the tags themselves: mindMap.storage is
// serialized into the .mm (verified: the attribute lands next to the <tags> element and
// survives save → close → reopen). A favorite names a tag OF THIS MAP, so a
// profile-global list — what the old TagFavoritesPanel used — would be meaningless here.
@Field final String FAVORITES_KEY = "unifiedTagPanel.favorites"
// PROFILE property, not map storage and not the view state: the option closes the panel,
// so it has to outlive it — and it is a preference about how the panel behaves, which has
// no business inside the user's .mm.
@Field final String CLOSE_AFTER_INSERT_KEY = "unifiedTagPanel.closeAfterInsert"
// Colour policy for tags created HERE (issue #2950). It is a policy of THIS panel: a tag
// born in Freeplane's own "Edit node tags", in a drag onto a node or in another script
// keeps Freeplane's behaviour. Hooking the app's tag creation globally is exactly what we
// are NOT doing — it would mean fighting the application on every version.
@Field final String NEW_TAG_COLOR_MODE_KEY = "unifiedTagPanel.newTagColorMode"
@Field final String NEW_TAG_COLOR_KEY = "unifiedTagPanel.newTagColor"
@Field final String SHOW_USAGE_COUNTS_KEY = "unifiedTagPanel.showUsageCounts"
// Second sorting mode (issue #2948 asked for it; it only became safe once the design said
// the hierarchy DISAPPEARS in this mode — with no tree on screen there is no manual order
// for a usage order to contradict). Off by default: the normal, hierarchical sort.
@Field final String SORT_BY_USAGE_KEY = "unifiedTagPanel.sortByUsage"
// "Show on every tab": ONE panel that moves to whatever tab is active, rather than one
// panel per tab. Same result for the stated purpose (it is always on screen), one set of
// state, no duplicated listeners. ⚠️ In a SPLIT view only the focused view carries it.
@Field final String FOLLOW_TABS_KEY = "unifiedTagPanel.followTabs"
// Filter vs highlight-only, the two search modes asked for in issue #2926 (comment
// 5073663796). ON (default) = what typing has always done here: the tags that do not match
// are hidden. OFF = nothing is hidden, the whole structure stays put and the matches are
// merely highlighted — which keeps the surrounding context and the scroll position.
@Field final String FILTER_HIDES_KEY = "unifiedTagPanel.filterHides"
@Field final String OPTIONS_DIALOG_KEY = "UnifiedTagPanelOptionsDialog"
@Field final String CLOSE_HANDLE_KEY = "UnifiedTagPanelCloseHandle"
@Field final String SUPPLIER_KEY = "UnifiedTagPanelSupplier"
// What the panel looked like when it was last hidden. Lives on the scroll pane (per tab),
// which outlives the panel — the @Fields do not.
@Field final String VIEW_STATE_KEY = "UnifiedTagPanelViewState"

@Field MapView boundMapView
@Field MapViewScrollPane boundScrollPane
// Where the overlay actually hangs. NOT the scroll pane: that one reports
// isOptimizedDrawingEnabled() == true, i.e. it promises Swing that its children never
// overlap — a promise we break the moment we put a panel over the viewport, and Swing then
// feels free to repaint the viewport alone, wiping us off the screen for a frame (the
// flicker seen with the Map Overview turned OFF; the overview being visible happened to
// mask it). Its PARENT, Freeplane's own MapViewPane, is the sanctioned overlay host:
// `isOptimizedDrawingEnabled() { return false; } // enable overlap`, and its layout ignores
// children added without constraints — which is exactly how the Map Overview itself hangs.
@Field Container overlayHost

@Field JPanel tagPanel
@Field JPanel favoritesStrip
@Field JTextField filterField
@Field Color filterFieldDefaultBackground
@Field JTree tagTree
@Field DefaultMutableTreeNode treeRootNode
@Field JScrollPane treeScrollPane
@Field JLabel statusLabel
@Field JButton wideButton
@Field JButton editModeButton
@Field JButton filterModeButton
@Field DefaultCellEditor treeCellEditor
@Field JTextField renameEditorField

@Field Timer retractTimer
@Field Timer resizeAnimationTimer
@Field Timer refreshTimer
@Field Timer filterDebounceTimer
@Field MouseListener hoverListener
@Field ComponentListener viewportListener
@Field Object reservedAreaSupplier
@Field Object selectionRelay
@Field Object mapChangeRelay
@Field Object nodeChangeRelay
@Field Object viewChangeRelay

@Field boolean wideMode = false
@Field boolean editMode = false
@Field boolean mouseOverPanel = false
@Field boolean popupOpen = false

@Field String filterText = ""
// expansion memory across rebuilds, keyed by qualified name (the tree object is replaced)
@Field final Set<String> expandedQns = new LinkedHashSet<String>()
@Field boolean firstBuildDone = false

// assignment markers for the current node selection (qualified names)
@Field final Set<String> assignedAll = new HashSet<String>()
@Field final Set<String> assignedSome = new HashSet<String>()

// favorite tags of THIS map, in user order (qualified names)
@Field final List<String> favorites = new ArrayList<String>()

// Usage counts (issue #2948). directUsage = nodes carrying EXACTLY this tag;
// categoryUsage = nodes anywhere in the category (the tag itself or any subtag), each
// node counted once. MEASURED at ~50 ms over 22k nodes, so this is cached and only
// recomputed when something can have changed it — never on a filter keystroke.
@Field Map<String, Integer> directUsage = new HashMap<String, Integer>()
@Field Map<String, Integer> categoryUsage = new HashMap<String, Integer>()
@Field boolean usageCountsStale = true
@Field boolean hideUnusedTags = false

// two-press delete — the KEYBOARD path only (the menu acts at once)
@Field String armedDeleteQn = null
@Field long armedDeleteAt = 0L

// row being renamed via F2 (the cell editor commits into it)
@Field Object renamingRow = null

// row being dragged (edit mode only); the flavor marks our own transfers
@Field Object draggedRow = null
@Field DataFlavor tagDndFlavor

// map filter state (same restore-the-folding contract as SearchPanel)
@Field boolean mapFilterActive = false
@Field final Set<NodeModel> nodesUnfoldedByFilter = new LinkedHashSet<NodeModel>()

@Field Font cachedItemFont
@Field final Map<Character, Character> accentFoldCache = new java.util.concurrent.ConcurrentHashMap<Character, Character>()

// glyph markers, replaced by fallbacks at startup if the font lacks them
@Field String markAll = "✓"
@Field String markSome = "◐"
@Field String editSymbol = "✎"
@Field String favoriteSymbol = "★"


// row payload for the tree. Top-level class: keep it dumb (no access to script methods).
class TagRow {
    String name            // leaf segment (or header text for synthetic rows)
    String qualifiedName   // full qualified content; null for synthetic rows
    List<String> path      // qualified segments; null for synthetic rows
    String colorHex        // #rrggbbaa from the API; may be null
    boolean uncategorized  // item of the uncategorized bucket
    boolean synthetic      // root / section header: not a tag
    String toString() { name }
}

// FlowLayout reports the preferred size of a SINGLE row, so wrapped rows get clipped in a
// narrow panel. This measures the rows for real. (Same implementation the user's
// TagFavoritesPanel carries; every Dimension read is cast because Groovy resolves
// .width/.height to the double-returning getters.)
class WrapLayout extends FlowLayout {
    WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap) }

    @Override
    Dimension preferredLayoutSize(Container target) { return layoutSize(target, true) }

    @Override
    Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false)
        return new Dimension((int) minimum.width - (getHgap() + 1), (int) minimum.height)
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            // during the first layout the target has no width yet: walk up until some
            // ancestor does, else assume unbounded (a single row) for this pass
            Container container = target
            int available = (int) container.getSize().width
            while (available == 0 && container.getParent() != null) {
                container = container.getParent()
                available = (int) container.getSize().width
            }
            if (available == 0) available = Integer.MAX_VALUE

            Insets insets = target.getInsets()
            int horizontalOverhead = insets.left + insets.right + getHgap() * 2
            int maxWidth = available - horizontalOverhead

            int totalWidth = 0
            int totalHeight = 0
            int rowWidth = 0
            int rowHeight = 0

            for (int i = 0; i < target.getComponentCount(); i++) {
                Component member = target.getComponent(i)
                if (!member.isVisible()) continue
                Dimension size = preferred ? member.getPreferredSize() : member.getMinimumSize()
                int memberWidth = (int) size.width
                int memberHeight = (int) size.height

                if (rowWidth != 0 && rowWidth + getHgap() + memberWidth > maxWidth) {
                    totalWidth = Math.max(totalWidth, rowWidth)
                    totalHeight += rowHeight + getVgap()
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth != 0) rowWidth += getHgap()
                rowWidth += memberWidth
                rowHeight = Math.max(rowHeight, memberHeight)
            }
            totalWidth = Math.max(totalWidth, rowWidth)
            totalHeight += rowHeight

            return new Dimension(totalWidth + horizontalOverhead,
                    totalHeight + insets.top + insets.bottom + getVgap() * 2)
        }
    }
}

class PanelSelectionRelay implements INodeSelectionListener {
    Closure handler
    @Override void onSelectionSetChange(IMapSelection selection) { handler.call() }
}

class PanelMapChangeRelay implements IMapChangeListener {
    Closure handler
    Closure structureHandler
    @Override void mapChanged(MapChangeEvent event) { handler.call(event) }
    // nodes coming and going change the usage counts without any tag event firing
    @Override void onNodeDeleted(NodeDeletionEvent event) { structureHandler.call() }
    @Override void onNodeInserted(NodeModel parent, NodeModel child, int newIndex) { structureHandler.call() }
}

class PanelNodeChangeRelay implements INodeChangeListener {
    Closure handler
    @Override void nodeChanged(NodeChangeEvent event) { handler.call(event) }
}

class PanelViewChangeRelay implements IMapViewChangeListener {
    Closure handler
    @Override void afterViewChange(Component oldView, Component newView) { handler.call(newView) }
}


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Main code ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

boundMapView = Controller.currentController.mapViewManager.mapView as MapView
if (boundMapView == null) return
boundScrollPane = SwingUtilities.getAncestorOfClass(MapViewScrollPane, boundMapView) as MapViewScrollPane
if (boundScrollPane == null) return   // view not anchored yet (mid map-switch); relaunch
overlayHost = resolveOverlayHost()

if (hidePanelIfOpen()) return

// closer registered BEFORE any external listener exists — every listener below is
// covered even if this run dies midway (the leak lesson from UtilityPanels)
boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, { -> closePanel() })

pickGlyphs()

retractTimer = new Timer(retractDelayMs, { ActionEvent e -> fitPanelBounds() } as ActionListener)
retractTimer.setRepeats(false)

refreshTimer = new Timer(refreshCoalesceMs, { ActionEvent e -> refreshTree() } as ActionListener)
refreshTimer.setRepeats(false)

filterDebounceTimer = new Timer(filterDebounceMs, { ActionEvent e -> applyFilterText() } as ActionListener)
filterDebounceTimer.setRepeats(false)

loadPanelPreferences()
createTagPanel()
loadFavorites()
restoreViewState()     // resume the hidden panel's expansion / filter / modes
startListeners()
refreshTree()          // builds the tree AND the favorites strip
updateAssignedMarks()

return

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Main code ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Lifecycle ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// The trigger is a TOGGLE: with the panel open it hides it, so one gesture shows and hides.
// (Opening already puts the focus in the filter field, which is why the earlier "second
// trigger refocuses / widens" behaviour was not worth the ambiguity.)
//
// "Healthy panel" = the component exists by name AND the closer exists in the client property.
// Since the closer is registered BEFORE the listeners, that dual presence guarantees the
// listeners are tracked. A panel by name WITHOUT a closer = leftover from an execution that
// broke; in that case we purge and open a fresh one, instead of toggling a zombie.
boolean hidePanelIfOpen() {
    JPanel existingPanel = overlayHost.components.find { it.name == PANEL_NAME } as JPanel
    Object closer = boundScrollPane.getClientProperty(CLOSE_HANDLE_KEY)

    if (existingPanel != null && closer != null) {
        closer.call()   // the closer of the round that opened it — it stashes the view state
        return true
    }

    purgePanelArtifacts()
    return false
}

// Carries the look of the panel across a hide/show cycle. The @Fields die with each run, so
// the state rides on the scroll pane — the same place (and lifetime) as the closer.
void disposeOptionsDialog() {
    Object dialog = boundScrollPane.getClientProperty(OPTIONS_DIALOG_KEY)
    if (dialog instanceof JDialog) ((JDialog) dialog).dispose()
    boundScrollPane.putClientProperty(OPTIONS_DIALOG_KEY, null)
}

void stashViewState() {
    boundScrollPane.putClientProperty(VIEW_STATE_KEY, [
            expanded  : new LinkedHashSet<String>(expandedQns),
            wide      : wideMode,
            edit      : editMode,
            hideUnused: hideUnusedTags,
            filter    : filterField != null ? filterField.getText() : ""
    ])
}

void restoreViewState() {
    Object stashed = boundScrollPane.getClientProperty(VIEW_STATE_KEY)
    if (!(stashed instanceof Map)) return
    Map state = (Map) stashed

    Object expanded = state.expanded
    if (expanded instanceof Collection) expandedQns.addAll((Collection<String>) expanded)
    // a restored state is deliberate — do NOT fall into the "first opening expands
    // everything" branch, even when the user had collapsed the whole tree
    firstBuildDone = true

    hideUnusedTags = state.hideUnused as boolean
    if (state.edit as boolean) toggleEditMode()
    if (state.wide as boolean) toggleWideMode()

    String text = String.valueOf(state.filter ?: "")
    if (!text.isEmpty()) {
        filterField.setText(text)
        // adopt it directly: the refreshTree in main already builds with this filter, and
        // the debounce that setText armed then finds nothing new to apply
        filterText = text.trim()
    }
}

// The MapViewPane, when it is really there. Recognised by name so the script does not have
// to import a package that is not part of the scripting API; falls back to the scroll pane
// (the old, flicker-prone spot) if Freeplane ever changes the hierarchy.
Container resolveOverlayHost() {
    Container parent = boundScrollPane.getParent()
    if (parent != null && parent.getClass().getSimpleName() == "MapViewPane") return parent
    return boundScrollPane
}

// bounds of a component of the host, expressed in the SCROLL PANE's coordinates — which is
// what the viewport's reserved-area logic expects (it subtracts the viewport's own x/y)
Rectangle boundsInScrollPane(Component component) {
    Rectangle bounds = component.getBounds()
    if (overlayHost.is(boundScrollPane)) return bounds
    return SwingUtilities.convertRectangle(overlayHost, bounds, boundScrollPane)
}

// viewport rectangle in the HOST's coordinates, so the panel can be pinned to its edges
Rectangle viewportBoundsInHost() {
    Rectangle bounds = boundScrollPane.getViewport().getBounds()
    if (overlayHost.is(boundScrollPane)) return bounds
    return SwingUtilities.convertRectangle(boundScrollPane, bounds, overlayHost)
}

Component findByName(Container container, String name) {
    for (Component component : container.components) {
        if (name == component.getName()) return component
        if (component instanceof Container) {
            Component found = findByName((Container) component, name)
            if (found != null) return found
        }
    }
    return null
}

void purgePanelArtifacts() {
    Object previousCloser = boundScrollPane.getClientProperty(CLOSE_HANDLE_KEY)
    if (previousCloser != null) previousCloser.call()
    disposeOptionsDialog()   // a zombie round may have left one open

    overlayHost.components
            .findAll { it.name == PANEL_NAME }
            .each { overlayHost.remove(it) }
    // a panel left behind by a round that hung it on the old host (the scroll pane)
    boundScrollPane.components
            .findAll { it.name == PANEL_NAME }
            .each { boundScrollPane.remove(it) }

    Object leftoverSupplier = boundScrollPane.getClientProperty(SUPPLIER_KEY)
    if (leftoverSupplier != null) {
        boundScrollPane.removeReservedAreaSupplier(leftoverSupplier)
        boundScrollPane.putClientProperty(SUPPLIER_KEY, null)
    }
    boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, null)

    boundScrollPane.revalidate()
    boundScrollPane.repaint()
}

// the three relays live on the MODE controller (global), not on the tab — closing the
// TAB does not run closePanel, so each handler self-heals: first event after the view
// dies removes them. The aliveness test is membership in the view manager's list, not
// isDisplayable(): a re-dock detaches components transiently and would false-positive.
boolean panelAlive() {
    if (tagPanel == null) return false
    try {
        // .is(), never ==: MapView implements Comparable and Groovy's == would say
        // every view equals every other. Verified fallback: a closed tab's scroll
        // pane stops being displayable.
        return Controller.currentController.mapViewManager.getMapViews().any { it.is(boundMapView) }
    } catch (Throwable t) {
        return boundScrollPane.isDisplayable()
    }
}

boolean aliveOrDetach() {
    if (panelAlive()) return true
    detachGlobalListeners()
    return false
}

void detachGlobalListeners() {
    def mapController = Controller.currentModeController.mapController
    if (selectionRelay != null) mapController.removeNodeSelectionListener(selectionRelay)
    if (mapChangeRelay != null) mapController.removeMapChangeListener(mapChangeRelay)
    if (nodeChangeRelay != null) mapController.removeNodeChangeListener(nodeChangeRelay)
    if (viewChangeRelay != null) {
        Controller.currentController.mapViewManager.removeMapViewChangeListener(viewChangeRelay)
    }
    selectionRelay = null
    mapChangeRelay = null
    nodeChangeRelay = null
    viewChangeRelay = null
}

void closePanel() {
    stashViewState()   // BEFORE anything is torn down: it reads the live widgets

    retractTimer.stop()
    refreshTimer.stop()
    filterDebounceTimer.stop()
    if (resizeAnimationTimer != null) {
        resizeAnimationTimer.stop()
        resizeAnimationTimer = null
    }
    if (tagTree != null && tagTree.isEditing()) tagTree.cancelEditing()
    // an options dialog is about THIS panel: it must not outlive it
    disposeOptionsDialog()

    // the filter this panel applied must not outlive its UI
    clearMapFilter(false)

    detachGlobalListeners()

    if (viewportListener != null) boundScrollPane.viewport.removeComponentListener(viewportListener)
    viewportListener = null

    if (reservedAreaSupplier != null) {
        boundScrollPane.removeReservedAreaSupplier(reservedAreaSupplier)
        reservedAreaSupplier = null
    }

    if (tagPanel != null) {
        overlayHost.remove(tagPanel)
        tagPanel = null
    }

    boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, null)
    boundScrollPane.putClientProperty(SUPPLIER_KEY, null)
    // VIEW_STATE_KEY deliberately survives: it is what the next opening resumes from

    overlayHost.revalidate()
    overlayHost.repaint()
    boundMapView.requestFocusInWindow()
}

void startListeners() {
    viewportListener = new ComponentAdapter() {
        @Override
        void componentResized(ComponentEvent e) { fitPanelBounds() }
    }
    boundScrollPane.viewport.addComponentListener(viewportListener)

    def mapController = Controller.currentModeController.mapController

    // node selection changed anywhere -> recompute the ✓/◐ marks for OUR view's selection
    selectionRelay = new PanelSelectionRelay(handler: { ->
        if (aliveOrDetach()) updateAssignedMarks()
    })
    mapController.addNodeSelectionListener(selectionRelay)

    // category structure or a tag color changed (any UI, undo included) -> refresh the tree
    mapChangeRelay = new PanelMapChangeRelay(
            handler: { MapChangeEvent event ->
                if (!aliveOrDetach()) return
                if (!event.map.is(boundMapView.map)) return
                if (event.property == TagCategories || event.property instanceof Tag) {
                    // a rename/move rewrites the tags of the nodes: the counts move with them
                    usageCountsStale = true
                    scheduleRefresh()
                }
            },
            structureHandler: { ->
                if (!aliveOrDetach()) return
                usageCountsStale = true   // a tagged node came or went
                scheduleRefresh()
            })
    mapController.addMapChangeListener(mapChangeRelay)

    // a node's tags changed (assignment from any UI) -> a new tag may have been
    // registered and the marks may be stale
    nodeChangeRelay = new PanelNodeChangeRelay(handler: { NodeChangeEvent event ->
        if (!aliveOrDetach()) return
        if (event.property == CoreTags) {
            usageCountsStale = true
            updateAssignedMarks()
            scheduleRefresh()
        }
    })
    mapController.addNodeChangeListener(nodeChangeRelay)

    // tab switched: with "show on every tab" on, the panel goes along
    viewChangeRelay = new PanelViewChangeRelay(handler: { Component newView ->
        if (!aliveOrDetach()) return
        followToView(newView)
    })
    Controller.currentController.mapViewManager.addMapViewChangeListener(viewChangeRelay)
}

void scheduleRefresh() {
    refreshTimer.restart()
}

boolean isFollowTabs() {
    try {
        return ResourceController.getResourceController().getBooleanProperty(FOLLOW_TABS_KEY, false)
    } catch (Throwable t) {
        return false
    }
}

// ⚠️ not named set*: see applyCloseAfterInsert
void applyFollowTabs(boolean enabled) {
    try {
        ResourceController.getResourceController().setProperty(FOLLOW_TABS_KEY, enabled)
    } catch (Throwable t) {
        showStatus("Could not save the option: " + t.getMessage())
    }
    showStatus(enabled ? "The panel will move to whatever tab you switch to"
                       : "The panel stays on this tab")
}

// Moves the SAME panel to the tab that just became active. Everything the panel is bolted
// to is per-tab — the overlay host, the reserved-area supplier, the viewport listener, the
// client properties — so each one has to be handed over. The three relays live on the mode
// controller instead, and their handlers read boundMapView, so they follow for free.
void followToView(Component newViewComponent) {
    if (tagPanel == null || !isFollowTabs()) return
    if (!(newViewComponent instanceof MapView)) return
    MapView newView = (MapView) newViewComponent
    if (newView.is(boundMapView)) return

    MapViewScrollPane newScrollPane =
            SwingUtilities.getAncestorOfClass(MapViewScrollPane, newView) as MapViewScrollPane
    if (newScrollPane == null) return   // the new view is not anchored yet; stay put

    // leave the tab we are leaving CLEAN: a map filter of ours must not outlive our presence
    clearMapFilter(false)
    if (tagTree != null && tagTree.isEditing()) tagTree.cancelEditing()
    disposeOptionsDialog()   // it is tied to the old tab's client property

    Container oldHost = overlayHost
    if (reservedAreaSupplier != null) boundScrollPane.removeReservedAreaSupplier(reservedAreaSupplier)
    if (viewportListener != null) boundScrollPane.viewport.removeComponentListener(viewportListener)
    oldHost.remove(tagPanel)
    boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, null)
    boundScrollPane.putClientProperty(SUPPLIER_KEY, null)

    boundMapView = newView
    boundScrollPane = newScrollPane
    overlayHost = resolveOverlayHost()

    overlayHost.add(tagPanel)
    overlayHost.setComponentZOrder(tagPanel, 0)
    if (reservedAreaSupplier != null) {
        boundScrollPane.addViewportReservedAreaSupplier(reservedAreaSupplier)
        boundScrollPane.putClientProperty(SUPPLIER_KEY, reservedAreaSupplier)
    }
    if (viewportListener != null) boundScrollPane.viewport.addComponentListener(viewportListener)
    boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, { -> closePanel() })

    oldHost.revalidate()
    oldHost.repaint()

    // another map means other tags, other favourites (they live in the .mm) and other counts
    loadFavorites()
    usageCountsStale = true
    // the remembered expansion is a list of qualified names of the map we just LEFT, so it
    // would land the new map collapsed. Start it the way opening the panel here would.
    expandedQns.clear()
    firstBuildDone = false
    refreshTree()
    updateAssignedMarks()
    fitPanelBounds()
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Lifecycle ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Panel ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void createTagPanel() {
    tagPanel = transparentPanel(new BorderLayout())
    tagPanel.setName(PANEL_NAME)
    tagPanel.setBorder(BorderFactory.createLineBorder(panelBorderColor(), panelBorderThickness))

    JPanel header = transparentPanel(new BorderLayout())
    header.add(createTitleBar(), BorderLayout.NORTH)
    header.add(createFilterBox(), BorderLayout.CENTER)
    header.add(createFavoritesStrip(), BorderLayout.SOUTH)

    tagPanel.add(header, BorderLayout.NORTH)
    tagPanel.add(createTreeArea(), BorderLayout.CENTER)

    statusLabel = new JLabel(" ")
    statusLabel.setFont(itemFont().deriveFont((float) (panelTextFontSize - 2)))
    statusLabel.setOpaque(true)
    statusLabel.setBackground(barColor)
    statusLabel.setForeground(barTextColor())
    tagPanel.add(statusLabel, BorderLayout.SOUTH)

    bindKey(tagPanel, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            KeyEvent.VK_ESCAPE, 0, "closeUnifiedTagPanel", { closePanel() })

    // reorder shortcuts work with either the field or the tree focused
    bindKey(tagPanel, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK, "tagMoveUp", { moveSelectedTag('up') })
    bindKey(tagPanel, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK, "tagMoveDown", { moveSelectedTag('down') })
    bindKey(tagPanel, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK, "tagPromote", { moveSelectedTag('promote') })
    bindKey(tagPanel, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK, "tagDemote", { moveSelectedTag('demote') })

    tagPanel.setBounds(0, 0, retractedWidth(), 10)

    // no constraint, ON PURPOSE: the MapViewPane's layout only lays out constrained
    // children, so ours keeps the bounds we set by hand — the same contract the Map
    // Overview uses. (On the scroll-pane fallback the layout ignores it just as well.)
    overlayHost.add(tagPanel)
    // add() puts the component BEHIND its siblings; z-order 0 = painted last = in front
    overlayHost.setComponentZOrder(tagPanel, 0)

    reservedAreaSupplier = { ->
        tagPanel != null && tagPanel.isVisible() ? boundsInScrollPane(tagPanel) : MapViewScrollPane.EMPTY_RECTANGLE
    } as MapViewScrollPane.ViewportReservedAreaSupplier
    boundScrollPane.addViewportReservedAreaSupplier(reservedAreaSupplier)
    boundScrollPane.putClientProperty(SUPPLIER_KEY, reservedAreaSupplier)

    hoverListener = new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) {
            mouseOverPanel = true
            retractTimer.stop()
            fitPanelBounds()
        }

        @Override
        void mouseExited(MouseEvent e) {
            mouseOverPanel = false
            retractTimer.restart()
        }
    }
    addHoverListenerRecursively(tagPanel)

    fitPanelBounds()
    overlayHost.revalidate()
    overlayHost.repaint()
    filterField.requestFocusInWindow()
}

JPanel createTitleBar() {
    Color barForeground = barTextColor()

    JPanel titleBar = new JPanel(new BorderLayout())
    titleBar.setOpaque(true)
    titleBar.setBackground(barColor)
    titleBar.setPreferredSize(new Dimension(0, titleBarHeight))

    JLabel title = new JLabel(" " + titleBarText)
    title.setFont(new Font(panelTextFontName, Font.BOLD, panelTextFontSize - 2))
    title.setForeground(barForeground)

    editModeButton = createBarButton(editSymbol, barForeground, editModeTooltip(), { toggleEditMode() })
    wideButton = createBarButton(wideOffSymbol, barForeground, wideTooltip(), { toggleWideMode() })
    JButton closeButton = createBarButton(closeButtonSymbol, barForeground, "Close the panel", { closePanel() })
    closeButton.addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) { closeButton.setForeground(Color.RED) }

        @Override
        void mouseExited(MouseEvent e) { closeButton.setForeground(barForeground) }
    })

    JPanel barButtons = transparentPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0))
    barButtons.add(editModeButton)
    barButtons.add(wideButton)
    barButtons.add(closeButton)

    titleBar.add(title, BorderLayout.CENTER)
    titleBar.add(barButtons, BorderLayout.EAST)
    return titleBar
}

JButton createBarButton(String symbol, Color barForeground, String tooltip, Closure action) {
    JButton button = new JButton(symbol)
    button.setFont(new Font(panelTextFontName, Font.BOLD, panelTextFontSize - 2))
    button.setForeground(barForeground)
    button.setToolTipText(tooltip)
    button.setPreferredSize(new Dimension(titleBarHeight, titleBarHeight))
    button.setOpaque(false)
    button.setContentAreaFilled(false)
    button.setBorderPainted(false)
    button.setFocusPainted(false)
    // the L&F margin leaves negative usable width on a narrow button -> "..."
    button.setMargin(new Insets(0, 0, 0, 0))
    Color hoverBackground = barHoverColor()
    button.addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) {
            button.setOpaque(true)
            if (button.getBackground() == null || !button.is(editModeButton) || !editMode) {
                button.setBackground(hoverBackground)
            }
            button.repaint()
        }

        @Override
        void mouseExited(MouseEvent e) {
            if (button.is(editModeButton) && editMode) {
                button.setBackground(editModeOnColor)
            } else {
                button.setOpaque(false)
            }
            button.repaint()
        }
    })
    button.addActionListener({ ActionEvent e -> action.call() } as ActionListener)
    return button
}

String wideTooltip() {
    return wideMode ? "Restore the normal width" : "Expand to " + wideWidthPercent + "% of the map and pin"
}

String editModeTooltip() {
    return editMode ? "Edit mode ON: clicks select, drag reorganizes. Click to go back to assigning."
                    : "Edit mode: clicks select (no toggling) and drag & drop reorganizes the hierarchy"
}

// ⚠️ do NOT extract into a set*(arg) method — Groovy would treat it as a property setter
// and silently skip the body (see SearchPanel's toggleWideMode note)
void toggleWideMode() {
    wideMode = !wideMode
    wideButton.setText(wideMode ? wideOnSymbol : wideOffSymbol)
    wideButton.setToolTipText(wideTooltip())
    fitPanelBounds()
}

void toggleEditMode() {
    editMode = !editMode
    editModeButton.setToolTipText(editModeTooltip())
    if (editMode) {
        editModeButton.setOpaque(true)
        editModeButton.setBackground(editModeOnColor)
    } else {
        editModeButton.setOpaque(false)
    }
    editModeButton.repaint()
    showStatus(editMode ? "Edit mode: clicks select, drag & drop reorganizes; assignment off"
                        : "Assign mode: click toggles the tag")
}

JPanel createFilterBox() {
    filterField = new JTextField() {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g)
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create()
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.setFont(getFont().deriveFont(Font.ITALIC))
                    g2.setColor(blendColors(getForeground(), getBackground(), 0.55f))
                    g2.drawString(filterFieldPlaceholder, getInsets().left,
                            getInsets().top + g2.getFontMetrics().getAscent())
                } finally {
                    g2.dispose()
                }
            }
        }
    }
    filterField.setName(FILTER_FIELD_NAME)
    filterField.setFont(itemFont())
    filterFieldDefaultBackground = filterField.getBackground()
    filterField.setBorder(BorderFactory.createCompoundBorder(
            filterField.getBorder(), BorderFactory.createEmptyBorder(2, 6, 2, 6)))

    // every keystroke arms the debounce (filterDebounceMs) instead of rebuilding the tree
    // right away; ENTER and the arrow keys flush whatever it still owes before acting
    filterField.getDocument().addDocumentListener(new DocumentListener() {
        @Override
        void insertUpdate(DocumentEvent e) { onFilterEdited() }

        @Override
        void removeUpdate(DocumentEvent e) { onFilterEdited() }

        @Override
        void changedUpdate(DocumentEvent e) { onFilterEdited() }
    })

    filterField.addFocusListener(new FocusAdapter() {
        @Override
        void focusGained(FocusEvent e) { fitPanelBounds() }

        @Override
        void focusLost(FocusEvent e) { retractTimer.restart() }
    })

    bindKey(filterField, JComponent.WHEN_FOCUSED, KeyEvent.VK_ENTER, 0,
            "assignBestMatch", { commitFieldAction(false) })
    bindKey(filterField, JComponent.WHEN_FOCUSED, KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK,
            "forceCreateTag", { commitFieldAction(true) })
    bindKey(filterField, JComponent.WHEN_FOCUSED, KeyEvent.VK_DOWN, 0,
            "nextTagRow", { moveTreeSelection(1) })
    bindKey(filterField, JComponent.WHEN_FOCUSED, KeyEvent.VK_UP, 0,
            "previousTagRow", { moveTreeSelection(-1) })

    JButton clearButton = new JButton(clearButtonSymbol)
    clearButton.setToolTipText("Clear the filter")
    clearButton.setFont(itemFont())
    clearButton.setPreferredSize(new Dimension(widthOfTheClearButton, 1))
    clearButton.setForeground(barTextColor())
    clearButton.setBackground(barColor)
    clearButton.setContentAreaFilled(false)
    clearButton.setOpaque(true)
    clearButton.setBorder(BorderFactory.createEmptyBorder())
    clearButton.setFocusPainted(false)
    Color clearHoverBackground = barHoverColor()
    clearButton.addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) { clearButton.setBackground(clearHoverBackground) }

        @Override
        void mouseExited(MouseEvent e) { clearButton.setBackground(barColor) }
    })
    clearButton.addActionListener({ ActionEvent e ->
        filterField.setText("")
        filterField.requestFocusInWindow()
    } as ActionListener)

    // the mode toggle sits right next to the search box, where #2926 asks for it
    filterModeButton = new JButton(isFilterHides() ? filterHidesSymbol : highlightOnlySymbol)
    filterModeButton.setName("UnifiedTagPanelFilterModeButton")
    filterModeButton.setToolTipText(filterModeTooltip())
    filterModeButton.setFont(itemFont())
    filterModeButton.setPreferredSize(new Dimension(widthOfTheClearButton, 1))
    filterModeButton.setForeground(barTextColor())
    filterModeButton.setBackground(barColor)
    filterModeButton.setContentAreaFilled(false)
    filterModeButton.setOpaque(true)
    filterModeButton.setBorder(BorderFactory.createEmptyBorder())
    filterModeButton.setFocusPainted(false)
    filterModeButton.addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) { filterModeButton.setBackground(barHoverColor()) }

        @Override
        void mouseExited(MouseEvent e) { filterModeButton.setBackground(barColor) }
    })
    filterModeButton.addActionListener({ ActionEvent e -> applyFilterHides(!isFilterHides()) } as ActionListener)

    // ⚠️ GridLayout, NOT FlowLayout: both buttons declare a preferred size of (width, 1) —
    // a deliberate lie that works only under a layout that STRETCHES them vertically, which
    // is what BorderLayout.EAST used to do when the clear button sat there alone. FlowLayout
    // honours the preferred size and the buttons collapse to 1px, leaving a blank strip.
    JPanel buttons = transparentPanel(new GridLayout(1, 2, 0, 0))
    buttons.add(filterModeButton)
    buttons.add(clearButton)

    JPanel filterBox = transparentPanel(new BorderLayout())
    filterBox.add(filterField, BorderLayout.CENTER)
    filterBox.add(buttons, BorderLayout.EAST)
    return filterBox
}

/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Usage counts ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// One pass over the map, tallying two things per node:
//  - each tag exactly as assigned            -> directUsage
//  - that tag AND every ancestor category    -> categoryUsage
// The per-node dedup (impliedHere) is what keeps a node tagged both 'work' and
// 'work::urgent' from being counted twice in the 'work' category.
void ensureUsageCounts() {
    if (!usageCountsStale) return
    usageCountsStale = false

    Map<String, Integer> direct = new HashMap<String, Integer>()
    Map<String, Integer> category = new HashMap<String, Integer>()
    String sep = separator()
    // the CORE reader, not the proxy: building a NodeProxy per node would dominate the cost
    IconController iconController = IconController.getController()

    List<NodeModel> stack = new ArrayList<NodeModel>()
    stack.add(boundMapView.getMap().getRootNode())
    Set<String> impliedHere = new HashSet<String>()
    while (!stack.isEmpty()) {
        NodeModel current = stack.remove(stack.size() - 1)
        List tags = iconController.getTags(current)
        if (!tags.isEmpty()) {
            impliedHere.clear()
            tags.each { tag ->
                String content = tag.getContent()
                direct.put(content, (direct.get(content) ?: 0) + 1)
                impliedHere.add(content)
                int at = content.indexOf(sep)
                while (at >= 0) {
                    impliedHere.add(content.substring(0, at))
                    at = content.indexOf(sep, at + sep.length())
                }
            }
            impliedHere.each { category.put(it, (category.get(it) ?: 0) + 1) }
        }
        current.getChildren().each { stack.add(it) }
    }

    directUsage = direct
    categoryUsage = category
}

String usageTooltip(TagRow row, boolean hasChildren) {
    if (row == null || row.qualifiedName == null) return null
    if (!showUsageCounts) return row.qualifiedName
    int direct = directUsageOf(row.qualifiedName)
    int total = categoryUsageOf(row.qualifiedName)
    StringBuilder text = new StringBuilder(row.qualifiedName)
    text.append(" — ").append(direct).append(direct == 1 ? " node" : " nodes")
    if (hasChildren && total != direct) {
        text.append("; ").append(total).append(" in the whole category")
    }
    if (total == 0) text.append(" (unused)")
    return text.toString()
}

int directUsageOf(String qn) {
    return qn == null ? 0 : (directUsage.get(qn) ?: 0)
}

int categoryUsageOf(String qn) {
    return qn == null ? 0 : (categoryUsage.get(qn) ?: 0)
}

boolean isTagUnused(String qn) {
    return categoryUsageOf(qn) == 0
}

boolean isSortByUsage() {
    try {
        return ResourceController.getResourceController().getBooleanProperty(SORT_BY_USAGE_KEY, false)
    } catch (Throwable t) {
        return false
    }
}

// ⚠️ not named set*: see applyCloseAfterInsert
void applySortByUsage(boolean enabled) {
    try {
        ResourceController.getResourceController().setProperty(SORT_BY_USAGE_KEY, enabled)
    } catch (Throwable t) {
        showStatus("Could not save the option: " + t.getMessage())
    }
    refreshTree()
}

// FLAT list, most used first — the categories stop being nesting and become part of each
// name (the qualified name is the label, or two tags called "done" under different
// categories would be indistinguishable once the tree is gone).
//
// The sort key is the CATEGORY usage, the same number that decides "unused" everywhere else
// (fading, hiding, the bulk delete). One number governs sorting, fading and hiding — a mode
// with a private definition of "used" would make those three disagree on screen.
void buildFlatUsageRows(DefaultMutableTreeNode root, def state, String needle) {
    List<Map> entries = []
    def collect
    collect = { cat ->
        entries.add([qn: cat.qualifiedName, path: new ArrayList<String>(cat.path),
                     color: cat.color, uncategorized: false])
        cat.children.each { collect(it) }
    }
    state.categories.each { collect(it) }
    state.uncategorizedTags.each { item ->
        entries.add([qn: item.qualifiedName, path: new ArrayList<String>(item.path),
                     color: item.color, uncategorized: true])
    }

    entries = entries.findAll { entry ->
        (!hideUnusedTags || categoryUsageOf((String) entry.qn) > 0) &&
                (needle.isEmpty() || foldAccents(((String) entry.qn).toLowerCase()).contains(needle))
    }
    entries.sort { a, b ->
        int byUsage = categoryUsageOf((String) b.qn) <=> categoryUsageOf((String) a.qn)
        byUsage != 0 ? byUsage : ((String) a.qn).compareToIgnoreCase((String) b.qn)
    }

    entries.each { entry ->
        root.add(new DefaultMutableTreeNode(new TagRow(
                name: (String) entry.qn, qualifiedName: (String) entry.qn,
                path: (List<String>) entry.path, colorHex: (String) entry.color,
                uncategorized: (boolean) entry.uncategorized)))
    }
}

// " (5)" for a leaf; " (2/17)" for a category whose subtags add uses of their own
String usageSuffix(TagRow row, boolean hasChildren) {
    if (!showUsageCounts || row == null || row.qualifiedName == null) return ""
    int direct = directUsageOf(row.qualifiedName)
    int total = categoryUsageOf(row.qualifiedName)
    if (!showCategoryTotals || !hasChildren || direct == total) return " (" + total + ")"
    return " (" + direct + "/" + total + ")"
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Usage counts ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Favorites ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

JPanel createFavoritesStrip() {
    favoritesStrip = transparentPanel(new WrapLayout(FlowLayout.LEFT, favoritesGapX, favoritesGapY))
    favoritesStrip.setName(FAVORITES_STRIP_NAME)
    favoritesStrip.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, panelBorderColor()))
    favoritesStrip.setVisible(false)   // no favorites yet: no wasted rows
    // dropping a tag dragged from the tree pins it here (edit mode, where dragging is on).
    // ⚠️ canImport does NOT require isDrop(): that keeps the handler testable through the
    // public paste-style TransferSupport constructor, and costs nothing.
    favoritesStrip.setTransferHandler(new TransferHandler() {
        @Override
        boolean canImport(TransferHandler.TransferSupport support) {
            return tagDndFlavor != null && support.isDataFlavorSupported(tagDndFlavor)
        }

        @Override
        boolean importData(TransferHandler.TransferSupport support) {
            if (!canImport(support)) return false
            String qn = String.valueOf(support.getTransferable().getTransferData(tagDndFlavor))
            return addFavorite(qn)
        }
    })
    return favoritesStrip
}

// storage of the MAP (persisted in the .mm, like the tags), one qualified name per line
void loadFavorites() {
    favorites.clear()
    try {
        Object stored = ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap.storage[FAVORITES_KEY]
        if (stored != null) {
            String.valueOf(stored).readLines().each { String line ->
                String qn = line.trim()
                if (!qn.isEmpty() && !favorites.contains(qn)) favorites.add(qn)
            }
        }
    } catch (Throwable t) {
        showStatus("Could not read the favorites: " + t.getMessage())
    }
}

// only ever called from an explicit user action: writing marks the map modified, and
// merely opening the panel must not dirty a map
void saveFavorites() {
    try {
        ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap.storage[FAVORITES_KEY] = favorites.join("\n")
    } catch (Throwable t) {
        showStatus("Could not save the favorites: " + t.getMessage())
    }
}

boolean isFavorite(String qn) {
    return qn != null && favorites.contains(qn)
}

boolean addFavorite(String qn) {
    if (qn == null || qn.isEmpty() || favorites.contains(qn)) return false
    favorites.add(qn)
    saveFavorites()
    rebuildFavoritesStrip()
    remeasureRows([qn])   // the row just grew a ★
    if (tagTree != null) tagTree.repaint()
    showStatus("'" + qn + "' added to the favorites of this map")
    return true
}

void removeFavorite(String qn) {
    if (!favorites.remove(qn)) return
    saveFavorites()
    rebuildFavoritesStrip()
    remeasureRows([qn])   // the ★ is gone; the row is narrower now
    if (tagTree != null) tagTree.repaint()
    showStatus("'" + qn + "' removed from the favorites")
}

void moveFavorite(String qn, int delta) {
    int index = favorites.indexOf(qn)
    if (index < 0) return
    int target = index + delta
    if (target < 0 || target >= favorites.size()) return
    favorites.remove(index)
    favorites.add(target, qn)
    saveFavorites()
    rebuildFavoritesStrip()
}

// keeps the favorites pointing at the right tag after OUR OWN structural edits:
// newQualifiedName null = the tag was deleted. Prefix match carries the descendants
// along (a favorite 'a::b::c' follows when 'a::b' is renamed or moved).
void remapFavorites(String oldQualifiedName, String newQualifiedName) {
    if (oldQualifiedName == null || favorites.isEmpty()) return
    String prefix = oldQualifiedName + separator()
    boolean changed = false

    List<String> updated = new ArrayList<String>()
    favorites.each { String qn ->
        String mapped
        if (qn == oldQualifiedName) {
            mapped = newQualifiedName
        } else if (qn.startsWith(prefix)) {
            mapped = newQualifiedName == null ? null
                    : newQualifiedName + separator() + qn.substring(prefix.length())
        } else {
            mapped = qn
        }
        if (mapped != qn) changed = true
        if (mapped != null && !updated.contains(mapped)) updated.add(mapped)
    }

    if (!changed) return
    favorites.clear()
    favorites.addAll(updated)
    saveFavorites()
    rebuildFavoritesStrip()
}

void rebuildFavoritesStrip() {
    if (favoritesStrip == null) return
    favoritesStrip.removeAll()
    favoritesStrip.setVisible(!favorites.isEmpty())
    favorites.each { String qn -> favoritesStrip.add(favoriteChip(qn)) }
    favoritesStrip.revalidate()
    favoritesStrip.repaint()
    fitPanelBounds()   // the strip grew or shrank: the panel height follows
}

JLabel favoriteChip(String qn) {
    boolean known = rowByQn(qn) != null
    Color background = colorForQualifiedName(qn)

    JLabel chip = new JLabel()
    chip.putClientProperty("tagQn", qn)
    chip.setOpaque(true)
    chip.setBackground(background)
    chip.setForeground(UITools.getTextColorForBackground(background))
    chip.setFont(itemFont().deriveFont((float) (panelTextFontSize - 1)))
    chip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(known ? background : Color.RED, 1),
            BorderFactory.createEmptyBorder(1, 5, 1, 5)))
    chip.setToolTipText(known ? qn : qn + " — no longer in this map (clicking recreates it)")
    applyChipText(chip)

    // MOUSE_PRESSED, like the tree: a micro-drag suppresses MOUSE_CLICKED
    chip.addMouseListener(new MouseAdapter() {
        @Override
        void mousePressed(MouseEvent e) {
            if (e.isPopupTrigger()) {
                showFavoriteMenu(chip, qn, e)
                return
            }
            if (SwingUtilities.isLeftMouseButton(e)) toggleTagQn(qn)
        }

        @Override
        void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger()) showFavoriteMenu(chip, qn, e)   // Windows fires it here
        }
    })
    // without this the panel would retract as soon as the cursor enters a chip: entering
    // a child fires mouseExited on the parent
    chip.addMouseListener(hoverListener)
    return chip
}

// the chip's own ✓/◐ marker, refreshed in place — no component churn on every selection change
void applyChipText(JLabel chip) {
    String qn = (String) chip.getClientProperty("tagQn")
    String marker = assignedAll.contains(qn) ? markAll + " " : assignedSome.contains(qn) ? markSome + " " : ""
    chip.setText(marker + shortNameOf(qn))
}

String shortNameOf(String qn) {
    String sep = separator()
    int at = qn.lastIndexOf(sep)
    return at < 0 ? qn : qn.substring(at + sep.length())
}

Color colorForQualifiedName(String qn) {
    TagRow row = rowByQn(qn)
    if (row != null) return chipColor(row)
    // unknown to this map (renamed elsewhere, or map switched): fall back to the color
    // Freeplane itself would derive from the content
    return new Tag(qn).getColor()
}

void showFavoriteMenu(JLabel chip, String qn, MouseEvent e) {
    JPopupMenu menu = new JPopupMenu()
    menu.add(menuItem("Assign to selected node(s)", { assignTagQn(qn) }))
    menu.add(menuItem("Remove from selected node(s)", { removeTagQn(qn) }))
    menu.addSeparator()
    if (rowByQn(qn) != null) {
        menu.add(menuItem("Show in the tree", { selectRowByQn(qn) }))
    }
    menu.add(menuItem("Move left", { moveFavorite(qn, -1) }))
    menu.add(menuItem("Move right", { moveFavorite(qn, 1) }))
    menu.addSeparator()
    menu.add(menuItem("Remove from favorites", { removeFavorite(qn) }))
    addPanelOptionItems(menu)
    attachPopupGuard(menu)
    menu.show(chip, e.getX(), e.getY())
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Favorites ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/

void onFilterEdited() {
    filterDebounceTimer.restart()
}

// applies whatever is in the field right now; returns false when nothing changed
boolean applyFilterText() {
    filterDebounceTimer.stop()
    String text = filterField.getText().trim()
    if (text == filterText) return false
    filterText = text
    refreshTree()
    return true
}

JComponent createTreeArea() {
    treeRootNode = new DefaultMutableTreeNode(new TagRow(name: "tags", synthetic: true))
    tagTree = new JTree(new DefaultTreeModel(treeRootNode)) {
        // JTree's default asks visibleRowCount × row height — same trap as JList;
        // the real preferred size lets fittedHeight() see the whole tree
        @Override
        Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize()
        }
    }
    tagTree.setRootVisible(false)
    tagTree.setShowsRootHandles(true)
    tagTree.setOpaque(false)
    tagTree.setRowHeight(0)   // ask the renderer per row
    tagTree.setFont(itemFont())
    tagTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION)
    tagTree.setToggleClickCount(0)   // expand only by the handle; double-click is not "expand"
    tagTree.setCellRenderer(createTagRenderer())

    renameEditorField = new JTextField()
    renameEditorField.setFont(itemFont())
    treeCellEditor = new DefaultCellEditor(renameEditorField) {
        // editing starts ONLY programmatically (F2 / context menu), never from clicks
        @Override
        boolean isCellEditable(java.util.EventObject anEvent) { return anEvent == null }
    }
    tagTree.setEditable(true)
    tagTree.setCellEditor(treeCellEditor)
    tagTree.setInvokesStopCellEditing(true)
    treeCellEditor.addCellEditorListener([
            editingStopped : { ChangeEvent e -> commitRename() },
            editingCanceled: { ChangeEvent e -> renamingRow = null }
    ] as CellEditorListener)

    tagTree.addTreeExpansionListener(new TreeExpansionListener() {
        @Override
        void treeExpanded(TreeExpansionEvent event) {
            TagRow row = rowOf(event.getPath())
            if (row != null && row.qualifiedName != null) expandedQns.add(row.qualifiedName)
            fitPanelBounds()
        }

        @Override
        void treeCollapsed(TreeExpansionEvent event) {
            TagRow row = rowOf(event.getPath())
            if (row != null && row.qualifiedName != null) expandedQns.remove(row.qualifiedName)
            fitPanelBounds()
        }
    })

    // MOUSE_PRESSED, not CLICKED: a micro-drag between press and release suppresses
    // CLICKED and the assignment silently misses (see the skill note / SingleClickAssign)
    tagTree.addMouseListener(new MouseAdapter() {
        @Override
        void mousePressed(MouseEvent e) {
            if (e.isPopupTrigger()) {
                showContextMenu(e)
                return
            }
            if (!SwingUtilities.isLeftMouseButton(e)) return
            TreePath path = tagTree.getPathForLocation(e.getX(), e.getY())
            if (path == null) return   // expand handle or empty area: let the tree handle it
            TagRow row = rowOf(path)
            if (row == null || row.synthetic) return
            if (!editMode) toggleTagOnSelection(row)
        }

        @Override
        void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger()) showContextMenu(e)   // Windows: popup trigger is on release
        }
    })

    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_ENTER, 0,
            "toggleSelectedTag", { TagRow row = selectedRow(); if (row != null) toggleTagOnSelection(row) })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_SPACE, 0,
            "toggleSelectedTagSpace", { TagRow row = selectedRow(); if (row != null) toggleTagOnSelection(row) })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_F2, 0,
            "renameSelectedTag", { startRename() })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_INSERT, 0,
            "addChildTag", { addChildToSelected() })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_DELETE, 0,
            "deleteSelectedTag", { deleteSelectedTag() })

    // drag & drop reorganization, EDIT MODE only (in assign mode a micro-drag would
    // assign the tag on press and then also start a drag). The handler dies with the
    // tree, so no closer entry is needed.
    tagDndFlavor = new DataFlavor('application/x-unified-tag-panel; class=java.lang.String', 'UnifiedTagPanel tag')
    tagTree.setDragEnabled(true)
    tagTree.setDropMode(DropMode.ON_OR_INSERT)
    tagTree.setTransferHandler(createTreeDndHandler())
    // programmatic drop for tests (a real TransferSupport with a DropLocation cannot be
    // built from outside): same planning + move path the mouse drop takes
    tagTree.putClientProperty('UnifiedTagPanelDropTest', { String draggedQn, String parentQn, Integer childIndex ->
        TagRow dragged = rowByQn(draggedQn)
        TagRow parent = parentQn == null ? null : (parentQn == '::uncategorized::' ? uncategorizedHeaderRow() : rowByQn(parentQn))
        if (dragged == null) return 'dragged not found'
        Map plan = planDropMove(dragged, parent, childIndex)
        if (plan == null) return 'rejected'
        return performDropMove(plan)
    })

    treeScrollPane = new JScrollPane(tagTree)
    treeScrollPane.setOpaque(false)
    treeScrollPane.getViewport().setOpaque(false)
    treeScrollPane.setBorder(BorderFactory.createEmptyBorder())
    return treeScrollPane
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Panel ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Tree model / rendering ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

def readState() {
    return ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap.tagCategories.read()
}

String separator() {
    try {
        return readState().categorySeparator ?: "::"
    } catch (Throwable t) {
        return "::"
    }
}

void refreshTree() {
    refreshTree(false)
}

/**
 * Rebuilds the whole tree from the live map state, keeping expansion, selection and filter.
 *
 * force=true rebuilds even with an inline rename open (cancelling it) — for the callers that
 * are themselves finishing that rename.
 */
void refreshTree(boolean force) {
    if (tagPanel == null || tagTree == null) return
    // never swap the model under an ACTIVE drag: the UI keeps repainting the drop line
    // from paths of the old model (same NPE as the importData note). Retry after.
    if (tagTree.getDropLocation() != null) {
        scheduleRefresh()
        return
    }
    // ⚠️ Nor under an inline rename: the refresh that lands here is usually OUR OWN edit coming
    // back through the map-change relay, and cancelling the editor made `Insert` unusable —
    // it created the tag, opened the rename, and ~150 ms later (refreshCoalesceMs) the editor
    // vanished before anyone could type, leaving the tag called "new tag". Postpone instead,
    // exactly like the drag guard above; when the edit ends, the retry finds a quiet tree.
    if (tagTree.isEditing()) {
        if (!force) {
            scheduleRefresh()
            return
        }
        tagTree.cancelEditing()
    }

    def state
    try {
        state = readState()
    } catch (Throwable t) {
        showStatus("Could not read tags: " + t.getMessage())
        return
    }

    // before building: the rows read the counts (and "hide unused" filters by them)
    ensureUsageCounts()

    String selectedQn = selectedRow()?.qualifiedName
    String needle = foldAccents(filterText.toLowerCase())

    // In highlight-only mode NOTHING is pruned by the text — the structure stays whole and
    // the matches are merely painted. The needle still drives matching, counting, the
    // arrows and the highlight; it just stops deciding who is visible.
    String pruningNeedle = isFilterHides() ? needle : ""

    DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode(new TagRow(name: "tags", synthetic: true))
    if (isSortByUsage()) {
        // no nesting and no "uncategorized" header: in this mode everything is one flat list
        buildFlatUsageRows(newRoot, state, pruningNeedle)
    } else {
        state.categories.each { cat ->
            DefaultMutableTreeNode child = buildCategoryNode(cat, pruningNeedle)
            if (child != null) newRoot.add(child)
        }

        List uncategorizedMatches = state.uncategorizedTags.findAll { item ->
            (!hideUnusedTags || categoryUsageOf(item.qualifiedName) > 0) &&
                    (pruningNeedle.isEmpty() || foldAccents(item.qualifiedName.toLowerCase()).contains(pruningNeedle))
        }
        if (!uncategorizedMatches.isEmpty()) {
            DefaultMutableTreeNode bucket = new DefaultMutableTreeNode(
                    new TagRow(name: "uncategorized", synthetic: true, qualifiedName: null))
            uncategorizedMatches.each { item ->
                bucket.add(new DefaultMutableTreeNode(new TagRow(
                        name: item.name, qualifiedName: item.qualifiedName,
                        path: new ArrayList<String>(item.path), colorHex: item.color,
                        uncategorized: true)))
            }
            newRoot.add(bucket)
        }
    }

    treeRootNode = newRoot
    ((DefaultTreeModel) tagTree.getModel()).setRoot(newRoot)

    // counted AFTER the tree is built, and only the rows that MATCH: an ancestor category
    // is on screen to show where a nested match lives, not as a result of its own. Same
    // predicate the arrow keys use, so "N tags match" is exactly how many the arrows stop on.
    int shown = countMatchingRows(newRoot)

    if (!firstBuildDone) {
        firstBuildDone = true
        collectAllCategoryQns(newRoot)   // first opening: everything expanded
    }
    restoreExpansion(needle)
    if (selectedQn != null) selectRowByQn(selectedQn)

    // under a filter, park the selection on the first MATCH — it is what ENTER will take
    // and where the arrows start, so the target is visible instead of implicit. The
    // previous selection is kept when it still matches (typing another letter must not
    // yank the choice away from the tag the user had already arrowed to).
    if (!needle.isEmpty() && !rowMatchesFilter(selectedRow())) {
        int first = firstNavigableRow()
        if (first >= 0) {
            tagTree.setSelectionRow(first)
            tagTree.scrollRowToVisible(first)
        } else {
            tagTree.clearSelection()
        }
    }

    int total = countTags(state)
    if (total == 0) {
        showStatus("No tags in this map yet — type and press Enter to create one")
    } else if (!needle.isEmpty()) {
        showStatus(shown + " of " + total + " tags match" +
                (isFilterHides() ? "" : " (highlighted, nothing hidden)") +
                (shown == 0 ? " — Enter creates '" + filterText + "'" : ""))
    } else if (showUsageCounts) {
        int unused = countUnusedTags(state)
        showStatus(total + " tags" + (unused > 0 ? " · " + unused + " unused" : "") +
                (hideUnusedTags ? " (unused hidden)" : "") +
                (isSortByUsage() ? " · by usage" : ""))
    } else {
        showStatus(total + " tags")
    }

    // colors and "still exists?" of the chips are read from the tree rows: rebuild after it
    rebuildFavoritesStrip()
    updateAssignedMarks()
    fitPanelBounds()
}

// include a category if its qualified name matches (the whole subtree inherits the
// match through the qualified prefix) or if any descendant matches
DefaultMutableTreeNode buildCategoryNode(def cat, String needle) {
    boolean selfMatches = needle.isEmpty() || foldAccents(cat.qualifiedName.toLowerCase()).contains(needle)
    // "hide unused" prunes by the CATEGORY count, so a used subtag keeps its parents visible
    if (hideUnusedTags && categoryUsageOf(cat.qualifiedName) == 0) return null

    List<DefaultMutableTreeNode> children = []
    cat.children.each { child ->
        DefaultMutableTreeNode built = buildCategoryNode(child, selfMatches ? "" : needle)
        if (built != null) children.add(built)
    }

    if (!selfMatches && children.isEmpty()) return null

    DefaultMutableTreeNode node = new DefaultMutableTreeNode(new TagRow(
            name: cat.name, qualifiedName: cat.qualifiedName,
            path: new ArrayList<String>(cat.path), colorHex: cat.color))
    children.each { node.add(it) }
    return node
}

int countMatchingRows(DefaultMutableTreeNode node) {
    int count = 0
    for (int i = 0; i < node.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i)
        if (rowMatchesFilter((TagRow) child.getUserObject())) count++
        count += countMatchingRows(child)
    }
    return count
}

int countTags(def state) {
    int count = state.uncategorizedTags.size()
    List stack = new ArrayList(state.categories)
    while (!stack.isEmpty()) {
        def cat = stack.remove(stack.size() - 1)
        count++
        stack.addAll(cat.children)
    }
    return count
}

int countUnusedTags(def state) {
    int count = state.uncategorizedTags.count { categoryUsageOf(it.qualifiedName) == 0 }
    List stack = new ArrayList(state.categories)
    while (!stack.isEmpty()) {
        def cat = stack.remove(stack.size() - 1)
        if (categoryUsageOf(cat.qualifiedName) == 0) count++
        stack.addAll(cat.children)
    }
    return count
}

// every tag nobody uses, TOP-MOST first: an unused category has only unused descendants
// (its count already includes them), so deleting the top removes the branch in one go —
// and passing both a parent and its child in the same request would break on the child.
List<Map> unusedTagsToDelete(def state) {
    List<Map> found = []
    state.uncategorizedTags.each { item ->
        if (categoryUsageOf(item.qualifiedName) == 0) {
            found.add([path: new ArrayList<String>(item.path), qn: item.qualifiedName, uncategorized: true])
        }
    }
    List stack = new ArrayList(state.categories)
    while (!stack.isEmpty()) {
        def cat = stack.remove(stack.size() - 1)
        if (categoryUsageOf(cat.qualifiedName) == 0) {
            found.add([path: new ArrayList<String>(cat.path), qn: cat.qualifiedName, uncategorized: false])
        } else {
            stack.addAll(cat.children)   // a used category may still hide unused subtags
        }
    }
    return found
}

void collectAllCategoryQns(DefaultMutableTreeNode node) {
    for (int i = 0; i < node.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i)
        TagRow row = (TagRow) child.getUserObject()
        if (row.qualifiedName != null && child.getChildCount() > 0) expandedQns.add(row.qualifiedName)
        collectAllCategoryQns(child)
    }
}

void restoreExpansion(String needle) {
    // filtering: the tree holds only matches + the path down to them, so expanding
    // everything IS revealing them (FilterableJTree's known limitation is exactly not doing this)
    if (!needle.isEmpty() && isFilterHides()) {
        int i = 0
        while (i < tagTree.getRowCount()) {
            tagTree.expandRow(i)
            i++
        }
        return
    }

    expandMatching(treeRootNode)
    // highlight-only: nothing was hidden, but a match inside a collapsed branch would still
    // be invisible. Open ONLY the paths leading to a match — the rest of the structure is
    // left exactly as the user had it, which is the point of this mode.
    if (!needle.isEmpty()) revealMatchAncestors(treeRootNode)
    // the synthetic uncategorized bucket stays always expanded
    for (int i = 0; i < treeRootNode.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) treeRootNode.getChildAt(i)
        TagRow row = (TagRow) child.getUserObject()
        if (row.synthetic) tagTree.expandPath(new TreePath(child.getPath()))
    }
}

// expands the ancestors of every matching row, top-down (expanding a node whose own parent
// is still collapsed would record the state but leave the row off screen)
void revealMatchAncestors(DefaultMutableTreeNode node) {
    for (int i = 0; i < node.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i)
        if (rowMatchesFilter((TagRow) child.getUserObject())) {
            TreePath path = new TreePath(child.getPath())
            List<TreePath> chain = []
            TreePath ancestor = path.getParentPath()
            while (ancestor != null && ancestor.getPathCount() > 1) {
                chain.add(0, ancestor)
                ancestor = ancestor.getParentPath()
            }
            chain.each { tagTree.expandPath(it) }
        }
        revealMatchAncestors(child)
    }
}

void expandMatching(DefaultMutableTreeNode node) {
    for (int i = 0; i < node.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i)
        TagRow row = (TagRow) child.getUserObject()
        if (row.qualifiedName != null && expandedQns.contains(row.qualifiedName)) {
            tagTree.expandPath(new TreePath(child.getPath()))
        }
        expandMatching(child)
    }
}

TagRow rowOf(TreePath path) {
    if (path == null) return null
    Object last = path.getLastPathComponent()
    if (!(last instanceof DefaultMutableTreeNode)) return null
    Object userObject = ((DefaultMutableTreeNode) last).getUserObject()
    return userObject instanceof TagRow ? (TagRow) userObject : null
}

TagRow selectedRow() {
    return rowOf(tagTree != null ? tagTree.getSelectionPath() : null)
}

DefaultMutableTreeNode findNodeByQn(DefaultMutableTreeNode from, String qn) {
    for (int i = 0; i < from.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) from.getChildAt(i)
        TagRow row = (TagRow) child.getUserObject()
        if (qn.equals(row.qualifiedName)) return child
        DefaultMutableTreeNode found = findNodeByQn(child, qn)
        if (found != null) return found
    }
    return null
}

void selectRowByQn(String qn) {
    DefaultMutableTreeNode node = findNodeByQn(treeRootNode, qn)
    if (node == null) return
    TreePath path = new TreePath(node.getPath())
    tagTree.expandPath(path.getParentPath())
    tagTree.setSelectionPath(path)
    tagTree.scrollPathToVisible(path)
}

// Down/Up walk ONLY the tags that match what was typed. An ancestor category is on screen
// merely to show where a match lives (filtering "thammy" still draws ProjetosEspecíficos >
// Híbrido above it); stopping on those would make the type-arrow-Enter flow land on the
// wrong tag. Wraps around at both ends.
void moveTreeSelection(int delta) {
    // typing and hitting Down right away must not walk the PREVIOUS tree: flush whatever
    // the debounce still owes, exactly as ENTER does
    applyFilterText()

    int rows = tagTree.getRowCount()
    if (rows == 0) return

    int step = delta > 0 ? 1 : -1
    int current = tagTree.getLeadSelectionRow()
    int candidate = current < 0 ? (delta > 0 ? 0 : rows - 1) : current + step

    for (int tried = 0; tried < rows; tried++) {
        int wrapped = ((candidate % rows) + rows) % rows
        if (isNavigableRow(wrapped)) {
            tagTree.setSelectionRow(wrapped)
            tagTree.scrollRowToVisible(wrapped)
            return
        }
        candidate = wrapped + step
    }
    // nothing to land on (only headers/non-matching ancestors): leave the selection alone
}

boolean isNavigableRow(int rowIndex) {
    return rowMatchesFilter(rowOf(tagTree.getPathForRow(rowIndex)))
}

// A row "matches" when the typed text is inside its QUALIFIED name — the same test that
// built the tree, so what is navigable is exactly what the filter selected. With no filter
// every real tag matches. (Note a category whose name contains the text matches too, and
// so do all of its subtags, since the text is in their qualified name as well.)
boolean rowMatchesFilter(TagRow row) {
    if (row == null || row.synthetic || row.qualifiedName == null) return false
    String needle = foldAccents(filterText.toLowerCase())
    if (needle.isEmpty()) return true
    return foldAccents(row.qualifiedName.toLowerCase()).contains(needle)
}

boolean isFilterHides() {
    try {
        return ResourceController.getResourceController().getBooleanProperty(FILTER_HIDES_KEY, true)
    } catch (Throwable t) {
        return true
    }
}

// ⚠️ not named set*: see applyCloseAfterInsert
void applyFilterHides(boolean hides) {
    try {
        ResourceController.getResourceController().setProperty(FILTER_HIDES_KEY, hides)
    } catch (Throwable t) {
        showStatus("Could not save the option: " + t.getMessage())
    }
    if (filterModeButton != null) {
        filterModeButton.setText(hides ? filterHidesSymbol : highlightOnlySymbol)
        filterModeButton.setToolTipText(filterModeTooltip())
    }
    refreshTree()
}

String filterModeTooltip() {
    return isFilterHides()
            ? "Filtering: tags that do not match are hidden. Click to only highlight instead."
            : "Highlighting only: every tag stays visible. Click to hide what does not match."
}

// Every occurrence of the typed text inside `text`, as [start, end) pairs. The ranges come
// from the FOLDED lowercase text, which by contract has the same length as the original
// (see foldAccents) — so they paint the original directly: typing "coracao" highlights
// "coração", accents and all.
List<int[]> matchRangesIn(String text, String foldedNeedle) {
    List<int[]> ranges = []
    if (foldedNeedle.isEmpty()) return ranges
    String haystack = foldAccents(text.toLowerCase())
    int at = haystack.indexOf(foldedNeedle)
    while (at >= 0) {
        ranges.add([at, at + foldedNeedle.length()] as int[])
        at = haystack.indexOf(foldedNeedle, at + foldedNeedle.length())
    }
    return ranges
}

// The row's own text with the matches wrapped in a highlight span; null when there is
// nothing to highlight, so the common case stays a plain (cheap) label.
//
// ⚠️ A row can be on screen because an ANCESTOR matched (filtering "agenda" shows
// "agenda::com data", whose own name holds no "agenda"). Those rows get no highlight, and
// that is right: the highlight sits on the segment that actually matched.
String highlightedFragment(String text) {
    String needle = foldAccents(filterText.toLowerCase())
    if (needle.isEmpty()) return null
    List<int[]> ranges = matchRangesIn(text, needle)
    if (ranges.isEmpty()) return null

    StringBuilder html = new StringBuilder()
    int cursor = 0
    ranges.each { int[] range ->
        html.append(HtmlUtils.toXMLEscapedText(text.substring(cursor, range[0])))
        html.append('<span style="background-color:').append(matchHighlightHex).append('; color:#000000;">')
        html.append(HtmlUtils.toXMLEscapedText(text.substring(range[0], range[1])))
        html.append('</span>')
        cursor = range[1]
    }
    html.append(HtmlUtils.toXMLEscapedText(text.substring(cursor)))
    return html.toString()
}

// the first row the arrows would land on, top down (-1 = none)
int firstNavigableRow() {
    for (int i = 0; i < tagTree.getRowCount(); i++) {
        if (isNavigableRow(i)) return i
    }
    return -1
}

TreeCellRenderer createTagRenderer() {
    JLabel label = new JLabel()
    label.setOpaque(true)
    return new TreeCellRenderer() {
        @Override
        Component getTreeCellRendererComponent(JTree tree, Object value, boolean isSelected,
                boolean expanded, boolean leaf, int rowIndex, boolean hasFocus) {
            TagRow row = (value instanceof DefaultMutableTreeNode
                    && ((DefaultMutableTreeNode) value).getUserObject() instanceof TagRow)
                    ? (TagRow) ((DefaultMutableTreeNode) value).getUserObject() : null

            label.setFont(itemFont())

            if (row == null || row.synthetic) {
                // section header: plain text, readable against the MAP (panel body is transparent)
                label.setOpaque(false)
                label.setText(row == null ? String.valueOf(value) : row.name)
                label.setToolTipText(null)   // the label is shared; without this it keeps the previous row's
                label.setForeground(UITools.getTextColorForBackground(mapBackground()))
                label.setFont(itemFont().deriveFont(Font.ITALIC, (float) (panelTextFontSize - 2)))
                label.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2))
                return label
            }

            boolean hasChildren = value instanceof DefaultMutableTreeNode &&
                    ((DefaultMutableTreeNode) value).getChildCount() > 0
            boolean unused = isTagUnused(row.qualifiedName)

            // an unused tag fades into the map instead of shouting in full color (#2948)
            Color chip = chipColor(row)
            if (unused && showUsageCounts) chip = blendColors(chip, mapBackground(), unusedTagFadeRatio)

            String marker = assignedAll.contains(row.qualifiedName) ? markAll + " "
                    : assignedSome.contains(row.qualifiedName) ? markSome + " " : ""
            String star = isFavorite(row.qualifiedName) ? favoriteSymbol : ""
            label.setOpaque(true)
            label.setBackground(chip)
            label.setForeground(UITools.getTextColorForBackground(chip))
            String prefix = star + marker
            String suffix = usageSuffix(row, hasChildren)
            String highlighted = highlightedFragment(row.name)
            if (highlighted == null) {
                label.setText(prefix + row.name + suffix)
            } else {
                // only the rows that actually match pay for HTML
                label.setText("<html>" + HtmlUtils.toXMLEscapedText(prefix) + highlighted
                        + HtmlUtils.toXMLEscapedText(suffix) + "</html>")
            }
            label.setToolTipText(usageTooltip(row, hasChildren))

            boolean armed = row.qualifiedName != null && row.qualifiedName.equals(armedDeleteQn)
            Color edge = armed ? Color.RED
                    : isSelected ? UITools.getTextColorForBackground(mapBackground())
                    : chip
            // ⚠️ ALWAYS 2px: only the COLOUR may depend on selection. A thicker border for
            // the selected row grows the label by 2px, and the tree had measured that row
            // while it was unselected — so selecting it clipped its own text with an
            // ellipsis. A highlight must never change the layout; when unselected the
            // border is painted in the chip's own colour and simply disappears.
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(edge, 2),
                    BorderFactory.createEmptyBorder(1, 5, 1, 5)))
            return label
        }
    }
}

// tag color composited over the map background when it carries alpha; opaque label
// with a translucent background would render artifacts
Color chipColor(TagRow row) {
    Color raw = parseTagColor(row.colorHex, row.qualifiedName ?: row.name)
    if (raw.getAlpha() == 255) return raw
    return blendColors(mapBackground(), new Color(raw.getRed(), raw.getGreen(), raw.getBlue()),
            raw.getAlpha() / 255f)
}

Color parseTagColor(String hex, String content) {
    if (hex == null || hex.length() < 7) return new Tag(content ?: "").getColor()
    try {
        int r = Integer.parseInt(hex.substring(1, 3), 16)
        int g = Integer.parseInt(hex.substring(3, 5), 16)
        int b = Integer.parseInt(hex.substring(5, 7), 16)
        int a = hex.length() >= 9 ? Integer.parseInt(hex.substring(7, 9), 16) : 255
        return new Color(r, g, b, a)
    } catch (Throwable t) {
        return new Tag(content ?: "").getColor()
    }
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Tree model / rendering ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Assigning (the Edit-Tags role) ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

List<NodeModel> selectedMapNodes() {
    def selection = boundMapView.getMapSelection()
    return selection == null ? [] : new ArrayList<NodeModel>(selection.getSelection())
}

List<String> tagsOf(NodeModel nodeModel) {
    return ProxyFactory.createNode(nodeModel, null).getTags().getTags()
}

void updateAssignedMarks() {
    if (tagTree == null) return

    Set<String> markedBefore = new HashSet<String>(assignedAll)
    markedBefore.addAll(assignedSome)

    assignedAll.clear()
    assignedSome.clear()
    List<NodeModel> selected = selectedMapNodes()
    if (!selected.isEmpty()) {
        List<Set<String>> perNode = selected.collect { new HashSet<String>(tagsOf(it)) }
        Set<String> union = new HashSet<String>()
        perNode.each { union.addAll(it) }
        union.each { qn ->
            if (perNode.every { it.contains(qn) }) assignedAll.add(qn)
            else assignedSome.add(qn)
        }
    }

    Set<String> markedNow = new HashSet<String>(assignedAll)
    markedNow.addAll(assignedSome)
    // a marker appearing or disappearing changes the row's WIDTH by ~14px, and a repaint
    // alone keeps the width the tree measured before it — see remeasureRows
    Set<String> changed = new HashSet<String>(markedBefore)
    changed.removeAll(markedNow)
    Set<String> appeared = new HashSet<String>(markedNow)
    appeared.removeAll(markedBefore)
    changed.addAll(appeared)
    remeasureRows(changed)

    tagTree.repaint()

    if (favoritesStrip != null) {
        favoritesStrip.components.each { if (it instanceof JLabel) applyChipText((JLabel) it) }
        favoritesStrip.repaint()
    }
}

void toggleTagOnSelection(TagRow row) {
    if (row == null || row.synthetic || row.qualifiedName == null) return
    toggleTagQn(row.qualifiedName)
}

// the tag is addressed by qualified name so the favorites chips (which may point at a tag
// the map no longer has) share the very same path as the tree
void toggleTagQn(String qn) {
    if (qn == null || qn.isEmpty()) return
    List<NodeModel> selected = selectedMapNodes()
    if (selected.isEmpty()) {
        showStatus("No node selected in the map")
        return
    }
    boolean allHave = selected.every { tagsOf(it).contains(qn) }
    int touched = 0
    selected.each { nodeModel ->
        def tags = ProxyFactory.createNode(nodeModel, null).getTags()
        if (allHave) {
            if (tags.remove(qn)) touched++
        } else if (!tags.getTags().contains(qn)) {
            tags.add(qn)
            touched++
        }
    }
    showStatus((allHave ? "Removed '" : "Assigned '") + qn + (allHave ? "' from " : "' to ")
            + touched + " node" + (touched == 1 ? "" : "s"))
    updateAssignedMarks()
    // assigning an unknown tag registers it on the map: the tree has to catch up
    if (!allHave && rowByQn(qn) == null) scheduleRefresh()
    if (!allHave) maybeCloseAfterInsert(touched)   // a removal never closes
}

void assignTagToSelection(TagRow row) {
    if (row == null || row.synthetic || row.qualifiedName == null) return
    assignTagQn(row.qualifiedName)
}

void assignTagQn(String qn) {
    List<NodeModel> selected = selectedMapNodes()
    if (selected.isEmpty()) {
        showStatus("No node selected in the map")
        return
    }
    int touched = 0
    selected.each { nodeModel ->
        def tags = ProxyFactory.createNode(nodeModel, null).getTags()
        if (!tags.getTags().contains(qn)) {
            tags.add(qn)
            touched++
        }
    }
    showStatus("Assigned '" + qn + "' to " + touched + " node" + (touched == 1 ? "" : "s"))
    updateAssignedMarks()
    if (rowByQn(qn) == null) scheduleRefresh()
    maybeCloseAfterInsert(touched)
}

void removeTagFromSelection(TagRow row) {
    if (row == null || row.synthetic || row.qualifiedName == null) return
    removeTagQn(row.qualifiedName)
}

void removeTagQn(String qn) {
    List<NodeModel> selected = selectedMapNodes()
    if (selected.isEmpty()) {
        showStatus("No node selected in the map")
        return
    }
    int touched = 0
    selected.each { nodeModel ->
        if (ProxyFactory.createNode(nodeModel, null).getTags().remove(qn)) touched++
    }
    showStatus("Removed '" + qn + "' from " + touched + " node" + (touched == 1 ? "" : "s"))
    updateAssignedMarks()
}

// ENTER in the field: assign the best match; nothing matches -> create the typed tag.
// Ctrl+ENTER: always create as typed. Mirrors the Edit-Tags "type, Enter, done" flow.
void commitFieldAction(boolean forceCreate) {
    // ENTER may arrive before the debounce fired: bring the tree up to date first, or
    // bestMatchRow() would pick from the previous keystroke's rows
    applyFilterText()

    String text = filterField.getText().trim()
    if (text.isEmpty()) return

    TagRow target = forceCreate ? null : bestMatchRow()
    // clear the field BEFORE assigning: the assignment may close the panel, and the view
    // state is stashed at that moment — a leftover filter would come back on the next open
    filterField.setText("")

    if (target != null) {
        assignTagToSelection(target)
        return
    }
    createAndAssignTag(text)
}

// What ENTER acts on: the selected row — but only if it MATCHES the filter. A selection
// left over on an ancestor shown for context (filtering 'thammy' still draws
// ProjetosEspecíficos) must not be what Enter assigns; in that case, and when nothing is
// selected, take the first matching row, the same one the arrows would land on first.
TagRow bestMatchRow() {
    TagRow selected = selectedRow()
    if (rowMatchesFilter(selected)) return selected

    int first = firstNavigableRow()
    if (first >= 0) return rowOf(tagTree.getPathForRow(first))

    // no match at all: fall back to the first real row on screen (an unfiltered tree with
    // only headers cannot happen, so this is just belt and braces)
    for (int i = 0; i < tagTree.getRowCount(); i++) {
        TagRow row = rowOf(tagTree.getPathForRow(i))
        if (row != null && !row.synthetic) return row
    }
    return null
}

// a tag created under a collapsed (or new) category would be born hidden — expand
// every ancestor level so the creation is visible
void revealAncestorsOf(String qualifiedText) {
    List<String> segments = qualifiedText.split(java.util.regex.Pattern.quote(separator())) as List<String>
    for (int i = 1; i < segments.size(); i++) {
        expandedQns.add(segments.subList(0, i).join(separator()))
    }
}

void createAndAssignTag(String qualifiedText) {
    revealAncestorsOf(qualifiedText)
    List<NodeModel> selected = selectedMapNodes()
    if (selected.isEmpty()) {
        // no node to assign to: still create the tag in the map's categories, under the
        // colour policy (here it must create even in "default" mode — creating IS the point)
        if (createMissingSegments(qualifiedText, true)) {
            showStatus("Created '" + qualifiedText + "' (no node selected, nothing assigned)")
        } else {
            showStatus("'" + qualifiedText + "' already exists (no node selected, nothing assigned)")
        }
        scheduleRefresh()
        return
    }

    // pre-create the missing levels already coloured; in "default" mode this is a no-op
    // and the assignment below registers the tag exactly as it always did
    createMissingSegments(qualifiedText, false)

    int touched = 0
    selected.each { nodeModel ->
        def tags = ProxyFactory.createNode(nodeModel, null).getTags()
        if (!tags.getTags().contains(qualifiedText)) {
            tags.add(qualifiedText)   // registers the tag (and its category path) on the map
            touched++
        }
    }
    showStatus("Created and assigned '" + qualifiedText + "' to " + touched + " node" + (touched == 1 ? "" : "s"))
    updateAssignedMarks()
    scheduleRefresh()
    maybeCloseAfterInsert(touched)
}

// preferences that live in the PROFILE and are read once per opening
void loadPanelPreferences() {
    try {
        showUsageCounts = ResourceController.getResourceController()
                .getBooleanProperty(SHOW_USAGE_COUNTS_KEY, showUsageCounts)
    } catch (Throwable t) {
        // keep the field's own default
    }
}

boolean isCloseAfterInsert() {
    try {
        return ResourceController.getResourceController()
                .getBooleanProperty(CLOSE_AFTER_INSERT_KEY, closeAfterInsertDefault)
    } catch (Throwable t) {
        return closeAfterInsertDefault
    }
}

// ⚠️ NOT named set*: in Groovy a set<Name>(arg) method IS the setter of the property
// <name>, and the call can be resolved as a field write with the body never running
// (the trap that cost a whole investigation in toggleWideMode).
void applyCloseAfterInsert(boolean enabled) {
    try {
        ResourceController.getResourceController().setProperty(CLOSE_AFTER_INSERT_KEY, enabled)
        showStatus(enabled ? "The panel will close as soon as a tag is assigned"
                           : "The panel stays open after assigning")
    } catch (Throwable t) {
        showStatus("Could not save the option: " + t.getMessage())
    }
}

// Called at the very END of every path that assigns, so nothing touches the widgets after
// the teardown. closePanel() also hands the focus back to the map, which is the point.
void maybeCloseAfterInsert(int assignedCount) {
    if (assignedCount <= 0 || tagPanel == null) return
    if (!isCloseAfterInsert()) return
    closePanel()
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Assigning (the Edit-Tags role) ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Colour policy (#2950) ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

String newTagColorMode() {
    try {
        return ResourceController.getResourceController()
                .getProperty(NEW_TAG_COLOR_MODE_KEY, newTagColorModeDefault)
    } catch (Throwable t) {
        return newTagColorModeDefault
    }
}

// null when no colour was ever picked — that is what lets "inherit" fall through to
// Freeplane's own default for a top-level tag, instead of silently painting it
String chosenFixedColor() {
    try {
        return ResourceController.getResourceController().getProperty(NEW_TAG_COLOR_KEY, null)
    } catch (Throwable t) {
        return null
    }
}

Map<String, String> colorByQualifiedName(def state) {
    Map<String, String> colors = new HashMap<String, String>()
    def walk
    walk = { cat ->
        colors.put(cat.qualifiedName, cat.color)
        cat.children.each { walk(it) }
    }
    state.categories.each { walk(it) }
    state.uncategorizedTags.each { colors.put(it.qualifiedName, it.color) }
    return colors
}

// Colour spec for each segment of a path being created, top-down. A null entry means
// "let Freeplane decide". ⚠️ It has to be per SEGMENT: creating 'a::b::c' from nothing
// creates three tags, and Freeplane gives EACH of them a colour of its own (measured) —
// colouring only the leaf would leave the new branch as random as before.
List<String> colorsForNewPath(List<String> path, Map<String, String> existingColors) {
    String mode = newTagColorMode()

    if (mode == "fixed") {
        String fixed = chosenFixedColor() ?: newTagColorFallback
        return path.collect { fixed }
    }
    if (mode != "inherit") {
        return path.collect { (String) null }   // "default": Freeplane keeps deciding
    }

    String separator = separator()
    String inherited = null
    List<String> colors = new ArrayList<String>()
    StringBuilder qualified = new StringBuilder()
    for (int i = 0; i < path.size(); i++) {
        if (i > 0) qualified.append(separator)
        qualified.append(path.get(i))
        String existing = existingColors.get(qualified.toString())
        // an ancestor that already exists sets the tone for everything below it
        if (existing != null) inherited = existing
        colors.add(existing != null ? existing : (inherited ?: chosenFixedColor()))
    }
    return colors
}

// Creates whatever is missing of a qualified path, each level already carrying the colour
// the policy asks for, in ONE instruction request. ✅ Verified: that request is a single
// undo step AND it composes with the assignment that follows — one Ctrl+Z takes back both.
//
// evenInDefaultMode=false keeps the "default" mode byte-for-byte as before: nothing is
// pre-created and the assignment itself registers the tag, exactly like it always did.
boolean createMissingSegments(String qualifiedText, boolean evenInDefaultMode) {
    if (!evenInDefaultMode && newTagColorMode() == "default") return false

    def state
    try {
        state = readState()
    } catch (Throwable t) {
        return false
    }

    Map<String, String> existing = colorByQualifiedName(state)
    List<String> path = qualifiedText.split(java.util.regex.Pattern.quote(separator())) as List<String>
    List<String> colors = colorsForNewPath(path, existing)

    String separator = separator()
    List instructions = []
    StringBuilder qualified = new StringBuilder()
    for (int i = 0; i < path.size(); i++) {
        if (i > 0) qualified.append(separator)
        qualified.append(path.get(i))
        if (existing.containsKey(qualified.toString())) continue
        instructions.add(new MapTagCategoryInstruction(MapTagCategoryInstructionType.ADD_TAG,
                new ArrayList<String>(path.subList(0, i + 1)), null, null,
                MapTagTargetLocation.CATEGORIZED, null, colors.get(i), null))
    }
    if (instructions.isEmpty()) return false

    try {
        def categories = ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap.tagCategories
        categories.edit(new MapTagCategoryInstructionRequest(categories.read().revision, instructions))
        return true
    } catch (Throwable t) {
        showStatus("Could not create '" + qualifiedText + "': " + t.getMessage())
        return false
    }
}

String hexOf(Color color) {
    return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue())
}

// the branch itself first, then every descendant (paths straight from the map state, so a
// filtered tree does not shrink what gets recoloured)
List<List<String>> branchPaths(def state, List<String> rootPath) {
    List<List<String>> paths = [new ArrayList<String>(rootPath)]
    List pending = new ArrayList(childrenAt(state, rootPath))
    while (!pending.isEmpty()) {
        def category = pending.remove(pending.size() - 1)
        paths.add(new ArrayList<String>(category.path))
        pending.addAll(category.children)
    }
    return paths
}

boolean hasChildrenInMap(TagRow row) {
    if (row == null || row.synthetic || row.uncategorized || row.path == null) return false
    try {
        return !childrenAt(readState(), row.path).isEmpty()
    } catch (Throwable t) {
        return false
    }
}

void chooseBranchColor(TagRow row) {
    Color chosen = JColorChooser.showDialog(tagPanel,
            "Color of '" + row.qualifiedName + "' and its sub-tags", chipColor(row))
    if (chosen == null) return
    applyBranchColor(row, hexOf(chosen))
}

// The whole branch in a SINGLE request — one undo step for the lot (verified). This is
// the explicit form of what #2950 asks for ("apply this color to all child tags"): an
// action the user picks from the menu, not a hidden mode that changes what a plain
// "Set color…" does.
void applyBranchColor(TagRow row, String colorSpec) {
    if (row == null || row.path == null || colorSpec == null) return

    def state
    try {
        state = readState()
    } catch (Throwable t) {
        showStatus("Could not read tags: " + t.getMessage())
        return
    }

    List<List<String>> paths = branchPaths(state, row.path)
    if (paths.size() <= 1) {
        applyTagColor(row, colorSpec)   // no sub-tags: a plain colour change
        return
    }

    try {
        def categories = ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap.tagCategories
        List instructions = paths.collect { List<String> path ->
            new MapTagCategoryInstruction(MapTagCategoryInstructionType.SET_COLOR, path, null, null,
                    MapTagTargetLocation.CATEGORIZED, null, colorSpec, null)
        }
        categories.edit(new MapTagCategoryInstructionRequest(categories.read().revision, instructions))
        showStatus("Colored " + paths.size() + " tags under '" + row.qualifiedName + "' — one Ctrl+Z undoes")
        refreshTree()
    } catch (Throwable t) {
        showStatus("Color change failed: " + t.getMessage())
    }
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Colour policy (#2950) ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Structure edits (the Manage-Categories role) ↓↓↓↓↓↓↓↓
*/

// every structural edit goes through the public instruction API: one undo step each,
// node tags rewritten along, optimistic-locked by the revision
def runInstruction(MapTagCategoryInstructionType type, Map args) {
    def mindMap = ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap
    def categories = mindMap.tagCategories
    def instruction = new MapTagCategoryInstruction(type,
            (List<String>) args.path, (String) args.newName, (List<String>) args.newParentPath,
            (MapTagTargetLocation) args.targetLocation, (Integer) args.index,
            (String) args.color, null)
    categories.edit(new MapTagCategoryInstructionRequest(categories.read().revision, [instruction]))
}

// sibling arithmetic verified in TagTreeKeyboardReorder: DOWN inserts two slots ahead
// (insert-then-remove), PROMOTE lands right after the old parent
void moveSelectedTag(String direction) {
    TagRow row = selectedRow()
    if (row == null || row.synthetic) {
        showStatus("Select a tag first")
        return
    }
    // the rows are in usage order, so "up" would move the tag somewhere unrelated to what
    // the eye expects — the same reason the drop is refused (see planDropMove)
    if (isSortByUsage()) {
        showStatus("Reordering is off while sorting by usage — switch back to the tree order")
        return
    }
    if (row.uncategorized) {
        showStatus("Uncategorized tags are sorted alphabetically — no manual order")
        return
    }

    def state
    try {
        state = readState()
    } catch (Throwable t) {
        showStatus("Could not read tags: " + t.getMessage())
        return
    }

    List<String> path = row.path
    List<String> parentPath = path.size() > 1 ? path.subList(0, path.size() - 1) as List<String> : []
    List siblings = childrenAt(state, parentPath)
    int index = siblings.findIndexOf { it.name == row.name }
    if (index < 0) {
        showStatus("Tag not found (changed underneath?) — refreshing")
        scheduleRefresh()
        return
    }

    List<String> newParentPath = null
    Integer newIndex = null
    switch (direction) {
        case 'up':
            if (index <= 0) { showStatus("Already first"); return }
            newParentPath = parentPath; newIndex = index - 1
            break
        case 'down':
            if (index >= siblings.size() - 1) { showStatus("Already last"); return }
            newParentPath = parentPath; newIndex = index + 2
            break
        case 'demote':
            if (index <= 0) { showStatus("No previous sibling to move under"); return }
            newParentPath = new ArrayList<String>(parentPath); newParentPath.add(siblings[index - 1].name)
            newIndex = null
            break
        case 'promote':
            if (path.size() < 2) { showStatus("Already at the top level"); return }
            List<String> grandParentPath = path.size() > 2 ? path.subList(0, path.size() - 2) as List<String> : []
            List parentSiblings = childrenAt(state, grandParentPath)
            int parentIndex = parentSiblings.findIndexOf { it.name == parentPath[parentPath.size() - 1] }
            newParentPath = grandParentPath; newIndex = parentIndex + 1
            break
        default:
            return
    }

    try {
        runInstruction(MapTagCategoryInstructionType.MOVE_TAG,
                [path: path, newParentPath: newParentPath, targetLocation: MapTagTargetLocation.CATEGORIZED,
                 index: newIndex])
        String newQn = (newParentPath.isEmpty() ? "" : newParentPath.join(separator()) + separator()) + row.name
        expandedQns.addAll(newParentPath.isEmpty() ? [] : [newParentPath.join(separator())])
        remapFavorites(row.qualifiedName, newQn)
        showStatus("Moved '" + row.name + "' (" + direction + ") — Ctrl+Z undoes")
        refreshTree()
        selectRowByQn(newQn)
    } catch (Throwable t) {
        showStatus("Move failed: " + t.getMessage())
    }
}

// children list at a category path in the state DTO ([] = top level)
List childrenAt(def state, List<String> path) {
    List current = state.categories
    for (String segment : path) {
        def next = current.find { it.name == segment }
        if (next == null) return []
        current = next.children
    }
    return current
}

void startRename() {
    TagRow row = selectedRow()
    if (row == null || row.synthetic) {
        showStatus("Select a tag first")
        return
    }
    renamingRow = row
    tagTree.startEditingAtPath(tagTree.getSelectionPath())
    renameEditorField.selectAll()
    fitPanelBounds()
}

void commitRename() {
    TagRow row = (TagRow) renamingRow
    renamingRow = null
    if (row == null) return
    String newName = renameEditorField.getText().trim()
    if (newName.isEmpty() || newName == row.name) return
    if (newName.contains(separator())) {
        showStatus("The name of one level cannot contain '" + separator() + "'")
        return
    }
    try {
        runInstruction(MapTagCategoryInstructionType.RENAME_TAG, [path: row.path, newName: newName,
                targetLocation: row.uncategorized ? MapTagTargetLocation.UNCATEGORIZED : MapTagTargetLocation.CATEGORIZED])
        List<String> parentPath = row.path.size() > 1 ? row.path.subList(0, row.path.size() - 1) : []
        String newQn = (parentPath.isEmpty() ? "" : parentPath.join(separator()) + separator()) + newName
        remapFavorites(row.qualifiedName, newQn)
        showStatus("Renamed to '" + newName + "' — node tags follow; Ctrl+Z undoes")
        // force: this runs FROM the editor's editingStopped, where isEditing() can still be
        // true — and postponing here would rebuild after selectRowByQn, losing the selection
        refreshTree(true)
        selectRowByQn(newQn)
    } catch (Throwable t) {
        showStatus("Rename failed: " + t.getMessage())
        scheduleRefresh()
    }
}

void addChildToSelected() {
    TagRow row = selectedRow()
    if (row == null || row.synthetic || row.uncategorized) {
        showStatus("Select a categorized tag first")
        return
    }
    addChildTag(row)
}

void addChildTag(TagRow parentRow) {
    def state
    try {
        state = readState()
    } catch (Throwable t) {
        showStatus("Could not read tags: " + t.getMessage())
        return
    }
    List existing = childrenAt(state, parentRow.path)
    String base = "new tag"
    String name = base
    int suffix = 2
    while (existing.any { it.name == name }) {
        name = base + " " + suffix
        suffix++
    }
    List<String> newPath = new ArrayList<String>(parentRow.path)
    newPath.add(name)
    // the child is born under the colour policy — this is the "new child tags inherit the
    // parent's colour" half of #2950
    String newColor = colorsForNewPath(newPath, colorByQualifiedName(state)).last()
    try {
        runInstruction(MapTagCategoryInstructionType.ADD_TAG,
                [path: newPath, targetLocation: MapTagTargetLocation.CATEGORIZED, color: newColor])
        expandedQns.add(parentRow.qualifiedName)
        refreshTree()
        String newQn = parentRow.qualifiedName + separator() + name
        selectRowByQn(newQn)
        // the rename editor really stays open now (see the isEditing guard in refreshTree), so
        // the hint is "type", not "press F2 because the editor disappeared"
        showStatus("Added '" + name + "' — type the name")
        startRename()
    } catch (Throwable t) {
        showStatus("Add failed: " + t.getMessage())
    }
}

// The deletion itself. Undoable in one step, so anything that got here through a
// deliberate gesture (picking it from the menu) just does it.
void deleteTagNow(TagRow row) {
    if (row == null || row.synthetic) return
    armedDeleteQn = null
    try {
        runInstruction(MapTagCategoryInstructionType.DELETE_TAG, [path: row.path,
                targetLocation: row.uncategorized ? MapTagTargetLocation.UNCATEGORIZED : MapTagTargetLocation.CATEGORIZED])
        remapFavorites(row.qualifiedName, null)
        showStatus("Deleted '" + row.qualifiedName + "' — Ctrl+Z undoes")
        refreshTree()
    } catch (Throwable t) {
        showStatus("Delete failed: " + t.getMessage())
    }
}

// KEYBOARD path only: a stray Delete with the tree focused should not destroy a tag, so the
// first press arms (the row turns red) and the second one within the window deletes.
// ⚠️ Do NOT reuse this for the context menu: there the choice was already deliberate, and
// since the menu closes on the first click, "confirming" would mean reopening the menu.
void deleteSelectedTag() {
    TagRow row = selectedRow()
    if (row == null || row.synthetic) return

    long now = System.currentTimeMillis()
    if (row.qualifiedName.equals(armedDeleteQn) && now - armedDeleteAt < deleteArmMs) {
        deleteTagNow(row)
        return
    }
    armedDeleteQn = row.qualifiedName
    armedDeleteAt = now
    tagTree.repaint()
    showStatus("Press Delete again to delete '" + row.qualifiedName + "' (and its subtags) from the map")
}

void chooseTagColor(TagRow row) {
    Color initial = chipColor(row)
    Color chosen = JColorChooser.showDialog(tagPanel, "Color of '" + row.qualifiedName + "'", initial)
    if (chosen == null) return
    applyTagColor(row, String.format("#%02x%02x%02x", chosen.getRed(), chosen.getGreen(), chosen.getBlue()))
}

void applyTagColor(TagRow row, String colorSpec) {
    try {
        runInstruction(MapTagCategoryInstructionType.SET_COLOR, [path: row.path, color: colorSpec,
                targetLocation: row.uncategorized ? MapTagTargetLocation.UNCATEGORIZED : MapTagTargetLocation.CATEGORIZED])
        showStatus((colorSpec == "none" ? "Color reset for '" : "Color set for '") + row.qualifiedName + "'")
        refreshTree()
    } catch (Throwable t) {
        showStatus("Color change failed: " + t.getMessage())
    }
}

void moveToUncategorized(TagRow row) {
    try {
        runInstruction(MapTagCategoryInstructionType.MOVE_TAG,
                [path: row.path, targetLocation: MapTagTargetLocation.UNCATEGORIZED])
        showStatus("Moved '" + row.qualifiedName + "' to uncategorized — Ctrl+Z undoes")
        refreshTree()
    } catch (Throwable t) {
        showStatus("Move failed: " + t.getMessage())
    }
}

void categorizeAtTopLevel(TagRow row) {
    try {
        runInstruction(MapTagCategoryInstructionType.MOVE_TAG,
                [path: row.path, newParentPath: [], targetLocation: MapTagTargetLocation.CATEGORIZED])
        showStatus("Moved '" + row.qualifiedName + "' to the top level — Ctrl+Z undoes")
        refreshTree()
        selectRowByQn(row.name)
    } catch (Throwable t) {
        showStatus("Move failed: " + t.getMessage())
    }
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Structure edits (the Manage-Categories role) ↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Drag & drop (edit mode only) ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

TransferHandler createTreeDndHandler() {
    return new TransferHandler() {
        @Override
        int getSourceActions(JComponent component) {
            return editMode ? TransferHandler.MOVE : TransferHandler.NONE
        }

        @Override
        Transferable createTransferable(JComponent component) {
            TagRow row = selectedRow()   // dragEnabled selects the pressed row before exporting
            if (!editMode || row == null || row.synthetic) return null
            draggedRow = row
            return new Transferable() {
                @Override
                DataFlavor[] getTransferDataFlavors() { return [tagDndFlavor] as DataFlavor[] }

                @Override
                boolean isDataFlavorSupported(DataFlavor flavor) { return flavor.equals(tagDndFlavor) }

                @Override
                Object getTransferData(DataFlavor flavor) {
                    if (!flavor.equals(tagDndFlavor)) throw new UnsupportedFlavorException(flavor)
                    return row.qualifiedName
                }
            }
        }

        @Override
        boolean canImport(TransferHandler.TransferSupport support) {
            if (!editMode || draggedRow == null) return false
            if (!support.isDataFlavorSupported(tagDndFlavor)) return false
            return dropPlanFrom(support) != null
        }

        @Override
        boolean importData(TransferHandler.TransferSupport support) {
            Map plan = dropPlanFrom(support)
            if (plan == null) return false
            // the model must NOT change inside importData: the DropHandler's cleanup runs
            // right AFTER it and repaints the drop line from the PRE-drop TreePath — a
            // synchronous rebuild leaves getPathBounds null and BasicTreeUI throws
            // "Cannot read field y because rect is null" (seen in the log)
            SwingUtilities.invokeLater { performDropMove(plan) }
            return true
        }

        @Override
        void exportDone(JComponent source, Transferable data, int action) {
            draggedRow = null   // the edit() already moved the tag; nothing to remove here
        }
    }
}

// maps the Swing drop location to a move plan. ON a row: childIndex == -1 and the path
// IS the target; INSERT: the path is the PARENT and childIndex the slot among children.
Map dropPlanFrom(TransferHandler.TransferSupport support) {
    if (!support.isDrop()) return null
    JTree.DropLocation location = (JTree.DropLocation) support.getDropLocation()
    TreePath path = location.getPath()
    if (path == null) return null
    TagRow pathRow = rowOf(path)
    TagRow parent = (pathRow == null || (pathRow.synthetic && pathRow.name == "tags")) ? null : pathRow
    int childIndex = location.getChildIndex()
    return planDropMove((TagRow) draggedRow, parent, childIndex < 0 ? null : childIndex)
}

// parentRow: null = top level; the synthetic "uncategorized" header (or one of its
// items) = the uncategorized bucket; childIndex: null = append (drop ON the parent).
// Returns null when the drop makes no sense — canImport then shows the no-drop cursor.
Map planDropMove(TagRow dragged, TagRow parentRow, Integer childIndex) {
    if (dragged == null || dragged.synthetic) return null
    // the visual childIndex would be computed against the FILTERED tree and would not
    // match the real sibling list the instruction acts on
    if (!filterText.isEmpty()) return null
    // and in usage order the rows are not siblings at all — there is no hierarchy on screen
    // to drop into
    if (isSortByUsage()) return null
    String sep = separator()

    boolean toUncategorized = parentRow != null &&
            (parentRow.uncategorized || (parentRow.synthetic && parentRow.name == "uncategorized"))
    if (toUncategorized) {
        if (dragged.uncategorized) return null   // the bucket is alphabetical: no manual order
        DefaultMutableTreeNode draggedNode = findNodeByQn(treeRootNode, dragged.qualifiedName)
        if (draggedNode != null && draggedNode.getChildCount() > 0) return null   // leaves only, like the native dialog
        return [path: dragged.path, newParentPath: null, targetLocation: MapTagTargetLocation.UNCATEGORIZED,
                index: null, newQn: dragged.name, expandQn: null]
    }

    if (parentRow != null && parentRow.synthetic) return null
    List<String> parentPath = parentRow == null ? [] : parentRow.path
    String parentQn = parentRow?.qualifiedName
    if (parentQn != null && !dragged.uncategorized) {
        if (parentQn == dragged.qualifiedName) return null                          // onto itself
        if (parentQn.startsWith(dragged.qualifiedName + sep)) return null           // into its own subtree
    }

    String oldParentQn = dragged.uncategorized ? "::uncategorized::"
            : (dragged.path.size() > 1 ? dragged.path.subList(0, dragged.path.size() - 1).join(sep) : null)
    boolean sameParent = !dragged.uncategorized && oldParentQn == parentQn
    if (sameParent) {
        if (childIndex == null) return null   // dropping ON the parent it is already in
        DefaultMutableTreeNode parentNode = parentRow == null ? treeRootNode : findNodeByQn(treeRootNode, parentQn)
        int oldIndex = indexAmongTagChildren(parentNode, dragged.qualifiedName)
        // the two slots around the current position land it where it already is
        if (oldIndex >= 0 && (childIndex == oldIndex || childIndex == oldIndex + 1)) return null
    }

    Integer index = childIndex
    if (index != null) {
        DefaultMutableTreeNode parentNode = parentRow == null ? treeRootNode : findNodeByQn(treeRootNode, parentQn)
        // at the top level the uncategorized bucket sits AFTER the last category: a drop
        // below it yields an index past the instruction's maximum — clamp to tag children
        index = Math.max(0, Math.min(index, tagChildCount(parentNode)))
    }

    String newQn = (parentPath.isEmpty() ? "" : parentPath.join(sep) + sep) + dragged.name
    return [path: dragged.path, newParentPath: parentPath, targetLocation: MapTagTargetLocation.CATEGORIZED,
            index: index, newQn: newQn, expandQn: parentQn]
}

String performDropMove(Map plan) {
    String oldQn = ((List<String>) plan.path).join(separator())
    try {
        runInstruction(MapTagCategoryInstructionType.MOVE_TAG,
                [path: plan.path, newParentPath: plan.newParentPath,
                 targetLocation: plan.targetLocation, index: plan.index])
        if (plan.expandQn != null) expandedQns.add((String) plan.expandQn)
        remapFavorites(oldQn, (String) plan.newQn)
        String message = "Moved '" + plan.newQn + "' — Ctrl+Z undoes"
        showStatus(message)
        refreshTree()
        selectRowByQn((String) plan.newQn)
        return message
    } catch (Throwable t) {
        String message = "Move failed: " + t.getMessage()
        showStatus(message)
        return message
    }
}

// ⚠️ `JTree` CACHES the width of each row (it asks the renderer once and remembers). Change
// what the renderer draws without telling the model and the row keeps the OLD width — the
// text then gets clipped with an ellipsis even with the panel wide open. MEASURED: the rows
// that gained a "✓ " wanted exactly 14px more than the tree had reserved, and only those
// were truncated. `repaint()` does NOT fix it; only a model event makes the tree re-measure.
void remeasureRows(Collection<String> qualifiedNames) {
    if (qualifiedNames.isEmpty() || tagTree == null) return
    DefaultTreeModel model = (DefaultTreeModel) tagTree.getModel()
    qualifiedNames.each { String qn ->
        DefaultMutableTreeNode node = findNodeByQn(treeRootNode, qn)
        if (node != null) model.nodeChanged(node)
    }
}

TagRow rowByQn(String qn) {
    DefaultMutableTreeNode node = findNodeByQn(treeRootNode, qn)
    return node == null ? null : (TagRow) node.getUserObject()
}

TagRow uncategorizedHeaderRow() {
    for (int i = 0; i < treeRootNode.getChildCount(); i++) {
        TagRow row = (TagRow) ((DefaultMutableTreeNode) treeRootNode.getChildAt(i)).getUserObject()
        if (row.synthetic && row.name == "uncategorized") return row
    }
    return null
}

int tagChildCount(DefaultMutableTreeNode parentNode) {
    if (parentNode == null) return 0
    int count = 0
    for (int i = 0; i < parentNode.getChildCount(); i++) {
        TagRow row = (TagRow) ((DefaultMutableTreeNode) parentNode.getChildAt(i)).getUserObject()
        if (!row.synthetic) count++
    }
    return count
}

int indexAmongTagChildren(DefaultMutableTreeNode parentNode, String qn) {
    if (parentNode == null) return -1
    int index = 0
    for (int i = 0; i < parentNode.getChildCount(); i++) {
        TagRow row = (TagRow) ((DefaultMutableTreeNode) parentNode.getChildAt(i)).getUserObject()
        if (row.synthetic) continue
        if (qn.equals(row.qualifiedName)) return index
        index++
    }
    return -1
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Drag & drop (edit mode only) ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Context menu ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void showContextMenu(MouseEvent e) {
    TreePath path = tagTree.getPathForLocation(e.getX(), e.getY())
    if (path == null) return
    tagTree.setSelectionPath(path)   // right-click selects, never toggles
    TagRow row = rowOf(path)
    if (row == null || row.synthetic) return

    JPopupMenu menu = new JPopupMenu()
    menu.add(menuItem("Assign to selected node(s)", { assignTagToSelection(row) }))
    menu.add(menuItem("Remove from selected node(s)", { removeTagFromSelection(row) }))
    menu.addSeparator()
    menu.add(isFavorite(row.qualifiedName)
            ? menuItem("Remove from favorites", { removeFavorite(row.qualifiedName) })
            : menuItem("Add to favorites  " + favoriteSymbol, { addFavorite(row.qualifiedName) }))
    menu.addSeparator()
    menu.add(menuItem("Rename  (F2)", { startRename() }))
    if (!row.uncategorized) {
        menu.add(menuItem("Add child tag  (Insert)", { addChildTag(row) }))
    }
    menu.add(menuItem("Delete", { deleteTagNow(row) }))
    menu.addSeparator()
    menu.add(menuItem("Set color…", { chooseTagColor(row) }))
    menu.add(menuItem("Reset color to default", { applyTagColor(row, "none") }))
    if (hasChildrenInMap(row)) {
        menu.add(menuItem("Set color for this and all sub-tags…", { chooseBranchColor(row) }))
        menu.add(menuItem("Recolor sub-tags to match this category",
                { applyBranchColor(row, row.colorHex) }))
    }
    if (!row.uncategorized) {
        menu.addSeparator()
        menu.add(menuItem("Move up  (Alt+↑)", { moveSelectedTag('up') }))
        menu.add(menuItem("Move down  (Alt+↓)", { moveSelectedTag('down') }))
        menu.add(menuItem("Promote  (Alt+←)", { moveSelectedTag('promote') }))
        menu.add(menuItem("Demote  (Alt+→)", { moveSelectedTag('demote') }))
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent()
        if (node.getChildCount() == 0) {
            menu.add(menuItem("Move to uncategorized", { moveToUncategorized(row) }))
        }
    } else {
        menu.addSeparator()
        menu.add(menuItem("Categorize at the top level", { categorizeAtTopLevel(row) }))
    }
    menu.addSeparator()
    menu.add(menuItem("Filter map by this tag", { filterMapByTag(row) }))
    if (mapFilterActive) {
        menu.add(menuItem("Clear the map filter", { clearMapFilter(true) }))
    }
    addUsageMenuItems(menu)
    addPanelOptionItems(menu)

    attachPopupGuard(menu)
    menu.show(tagTree, e.getX(), e.getY())
}

// the popup is a separate window: leaving the panel for it fires mouseExited and the
// panel would retract from under the open menu
void attachPopupGuard(JPopupMenu menu) {
    menu.addPopupMenuListener([
            popupMenuWillBecomeVisible  : { PopupMenuEvent ev -> popupOpen = true },
            popupMenuWillBecomeInvisible: { PopupMenuEvent ev -> popupOpen = false; retractTimer.restart() },
            popupMenuCanceled           : { PopupMenuEvent ev -> popupOpen = false }
    ] as PopupMenuListener)
}

// panel behaviour, reachable from every context menu the panel shows
void addPanelOptionItems(JPopupMenu menu) {
    menu.addSeparator()
    JCheckBoxMenuItem closeItem = new JCheckBoxMenuItem("Close after insert", isCloseAfterInsert())
    closeItem.setToolTipText("Hide the panel as soon as a tag is assigned — trigger, type, Enter, back to the map")
    closeItem.addActionListener({ ActionEvent e ->
        applyCloseAfterInsert(closeItem.isSelected())
    } as ActionListener)
    menu.add(closeItem)
    menu.add(menuItem("Options…", { showOptionsDialog() }))
}

/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Options dialog ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// NON-modal, on purpose: a modal dialog blocks the EDT (and hangs a script host outright),
// and the panel's whole idiom is non-blocking. It applies on the spot — no OK/Cancel, no
// pending state to forget about — and it dies with the panel it configures.
void showOptionsDialog() {
    Object opened = boundScrollPane.getClientProperty(OPTIONS_DIALOG_KEY)
    if (opened instanceof JDialog && ((JDialog) opened).isDisplayable()) {
        ((JDialog) opened).toFront()
        return
    }

    JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(tagPanel),
            "Tag panel options", Dialog.ModalityType.MODELESS)
    dialog.setName(OPTIONS_DIALOG_KEY)

    JPanel content = new JPanel()
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS))
    content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12))

    JLabel parentPreview = previewChip("parent")
    JLabel childPreview = previewChip("child")
    JButton colorButton = new JButton("      ")
    colorButton.setName("UnifiedTagPanelColorButton")

    JRadioButton defaultMode = new JRadioButton("Freeplane default (color from the name)")
    JRadioButton inheritMode = new JRadioButton("Inherit from the parent category")
    JRadioButton fixedMode = new JRadioButton("Fixed color")
    defaultMode.setName("UnifiedTagPanelModeDefault")
    inheritMode.setName("UnifiedTagPanelModeInherit")
    fixedMode.setName("UnifiedTagPanelModeFixed")
    ButtonGroup modes = new ButtonGroup()
    [defaultMode, inheritMode, fixedMode].each { modes.add(it) }

    Closure refreshOptionWidgets = { ->
        String mode = newTagColorMode()
        defaultMode.setSelected(mode == "default")
        inheritMode.setSelected(mode == "inherit")
        fixedMode.setSelected(mode == "fixed")
        colorButton.setEnabled(mode != "default")
        Color fixed = parseTagColor(chosenFixedColor() ?: newTagColorFallback, "")
        colorButton.setBackground(fixed)
        colorButton.setOpaque(true)
        colorButton.setBorderPainted(false)
        applyPreviewChips(parentPreview, childPreview)
    }

    Closure chooseMode = { String mode ->
        try {
            ResourceController.getResourceController().setProperty(NEW_TAG_COLOR_MODE_KEY, mode)
        } catch (Throwable t) {
            showStatus("Could not save the option: " + t.getMessage())
        }
        refreshOptionWidgets.call()
    }
    defaultMode.addActionListener({ ActionEvent e -> chooseMode.call("default") } as ActionListener)
    inheritMode.addActionListener({ ActionEvent e -> chooseMode.call("inherit") } as ActionListener)
    fixedMode.addActionListener({ ActionEvent e -> chooseMode.call("fixed") } as ActionListener)

    colorButton.addActionListener({ ActionEvent e ->
        Color chosen = JColorChooser.showDialog(dialog, "Color of new tags",
                parseTagColor(chosenFixedColor() ?: newTagColorFallback, ""))
        if (chosen == null) return
        try {
            ResourceController.getResourceController().setProperty(NEW_TAG_COLOR_KEY, hexOf(chosen))
        } catch (Throwable t) {
            showStatus("Could not save the color: " + t.getMessage())
        }
        refreshOptionWidgets.call()
    } as ActionListener)

    content.add(sectionLabel("New tags"))
    [defaultMode, inheritMode].each { content.add(leftAligned(it)) }
    JPanel fixedRow = transparentPanel(new FlowLayout(FlowLayout.LEFT, 6, 0))
    fixedRow.setOpaque(false)
    fixedRow.add(fixedMode)
    fixedRow.add(colorButton)
    content.add(leftAligned(fixedRow))
    JPanel previewRow = transparentPanel(new FlowLayout(FlowLayout.LEFT, 6, 0))
    previewRow.add(new JLabel("Preview:"))
    previewRow.add(parentPreview)
    previewRow.add(childPreview)
    content.add(leftAligned(previewRow))

    content.add(Box.createVerticalStrut(10))
    content.add(sectionLabel("Behaviour"))

    JCheckBox closeAfterInsertBox = new JCheckBox("Close after insert", isCloseAfterInsert())
    closeAfterInsertBox.setName("UnifiedTagPanelCloseAfterInsertBox")
    closeAfterInsertBox.addActionListener({ ActionEvent e ->
        applyCloseAfterInsert(closeAfterInsertBox.isSelected())
    } as ActionListener)
    content.add(leftAligned(closeAfterInsertBox))

    JCheckBox followTabsBox = new JCheckBox("Show on every tab", isFollowTabs())
    followTabsBox.setName("UnifiedTagPanelFollowTabsBox")
    followTabsBox.setToolTipText("The panel moves to whatever tab you switch to, instead of staying on the one it was opened in")
    followTabsBox.addActionListener({ ActionEvent e ->
        applyFollowTabs(followTabsBox.isSelected())
    } as ActionListener)
    content.add(leftAligned(followTabsBox))

    JCheckBox usageCountsBox = new JCheckBox("Show usage counts", showUsageCounts)
    usageCountsBox.setName("UnifiedTagPanelUsageCountsBox")
    usageCountsBox.addActionListener({ ActionEvent e ->
        showUsageCounts = usageCountsBox.isSelected()
        try {
            ResourceController.getResourceController().setProperty(SHOW_USAGE_COUNTS_KEY, showUsageCounts)
        } catch (Throwable t) {
            showStatus("Could not save the option: " + t.getMessage())
        }
        refreshTree()
    } as ActionListener)
    content.add(leftAligned(usageCountsBox))

    content.add(Box.createVerticalStrut(10))
    JLabel footnote = new JLabel("<html><i>Colors apply to tags created in this panel.<br>"
            + "Tags made elsewhere in Freeplane keep its own default.</i></html>")
    footnote.setFont(footnote.getFont().deriveFont((float) (panelTextFontSize - 3)))
    content.add(leftAligned(footnote))

    JButton closeButton = new JButton("Close")
    closeButton.addActionListener({ ActionEvent e -> dialog.dispose() } as ActionListener)
    JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0))
    buttonRow.add(closeButton)
    content.add(Box.createVerticalStrut(6))
    content.add(leftAligned(buttonRow))

    refreshOptionWidgets.call()

    dialog.getContentPane().add(content)
    dialog.pack()
    dialog.setLocationRelativeTo(tagPanel)
    dialog.addWindowListener(new WindowAdapter() {
        @Override
        void windowClosed(WindowEvent e) {
            boundScrollPane.putClientProperty(OPTIONS_DIALOG_KEY, null)
            refreshTree()   // the policy may have changed what the tree should look like
        }
    })
    boundScrollPane.putClientProperty(OPTIONS_DIALOG_KEY, dialog)
    dialog.setVisible(true)
}

JLabel sectionLabel(String text) {
    JLabel label = new JLabel(text)
    label.setFont(label.getFont().deriveFont(Font.BOLD))
    label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0))
    return label
}

// BoxLayout centres whatever does not say otherwise
JComponent leftAligned(JComponent component) {
    component.setAlignmentX(Component.LEFT_ALIGNMENT)
    return component
}

JLabel previewChip(String text) {
    JLabel chip = new JLabel(text)
    chip.setOpaque(true)
    chip.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6))
    return chip
}

// shows what the policy WOULD paint: a brand new top-level tag, and a child under it
void applyPreviewChips(JLabel parentChip, JLabel childChip) {
    Map<String, String> nothingExists = new HashMap<String, String>()
    List<String> parentColors = colorsForNewPath(["parent"], nothingExists)
    Color parentColor = parseTagColor(parentColors.get(0), "parent")

    // for the child, pretend the parent now exists with the colour above — which is
    // exactly the state the real creation would be in
    Map<String, String> parentExists = new HashMap<String, String>()
    parentExists.put("parent", hexOf(parentColor))
    List<String> childColors = colorsForNewPath(["parent", "child"], parentExists)
    Color childColor = parseTagColor(childColors.get(1), "parent" + separator() + "child")

    parentChip.setBackground(parentColor)
    parentChip.setForeground(UITools.getTextColorForBackground(parentColor))
    childChip.setBackground(childColor)
    childChip.setForeground(UITools.getTextColorForBackground(childColor))
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Options dialog ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/

// panel-wide maintenance driven by the counts (issue #2948)
void addUsageMenuItems(JPopupMenu menu) {
    if (!showUsageCounts) return
    def state
    try {
        state = readState()
    } catch (Throwable t) {
        return
    }
    int unused = countUnusedTags(state)

    menu.addSeparator()
    JCheckBoxMenuItem hideItem = new JCheckBoxMenuItem("Hide unused tags", hideUnusedTags)
    hideItem.addActionListener({ ActionEvent e ->
        hideUnusedTags = hideItem.isSelected()
        refreshTree()
    } as ActionListener)
    hideItem.setEnabled(unused > 0 || hideUnusedTags)
    menu.add(hideItem)

    JCheckBoxMenuItem sortItem = new JCheckBoxMenuItem("Sort by usage", isSortByUsage())
    sortItem.setToolTipText("Drop the category nesting and list every tag, most used first")
    sortItem.addActionListener({ ActionEvent e -> applySortByUsage(sortItem.isSelected()) } as ActionListener)
    menu.add(sortItem)

    // Bulk and destructive, so it asks — but as a SUBMENU, which confirms in the same
    // gesture. (An "arm, then pick it again" confirmation would force the user to reopen
    // the menu, the very annoyance that got the single Delete fixed.)
    JMenu deleteUnused = new JMenu("Delete all unused tags (" + unused + ")")
    deleteUnused.setEnabled(unused > 0)
    deleteUnused.add(menuItem("Confirm — no node uses them, and Ctrl+Z undoes",
            { deleteAllUnusedTags() }))
    menu.add(deleteUnused)
}

// Deleting tags that no node uses cannot change any node's tags. The confirmation lives in
// the menu (a submenu, see addUsageMenuItems) rather than in a modal dialog: modal blocks
// the EDT and hangs a script host outright, and the panel's idiom is non-modal feedback in
// the status bar. The whole batch is ONE instruction request, hence one undo step.
void deleteAllUnusedTags() {
    def state
    try {
        state = readState()
    } catch (Throwable t) {
        showStatus("Could not read tags: " + t.getMessage())
        return
    }
    List<Map> victims = unusedTagsToDelete(state)
    if (victims.isEmpty()) {
        showStatus("No unused tags")
        return
    }

    try {
        def mindMap = ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap
        def categories = mindMap.tagCategories
        List instructions = victims.collect { victim ->
            new MapTagCategoryInstruction(MapTagCategoryInstructionType.DELETE_TAG,
                    (List<String>) victim.path, null, null,
                    victim.uncategorized ? MapTagTargetLocation.UNCATEGORIZED : MapTagTargetLocation.CATEGORIZED,
                    null, null, null)
        }
        categories.edit(new MapTagCategoryInstructionRequest(categories.read().revision, instructions))
        victims.each { remapFavorites((String) it.qn, null) }
        showStatus("Deleted " + victims.size() + " unused tag" + (victims.size() == 1 ? "" : "s") + " — Ctrl+Z undoes")
        refreshTree()
    } catch (Throwable t) {
        showStatus("Delete failed: " + t.getMessage())
    }
}

JMenuItem menuItem(String text, Closure action) {
    JMenuItem item = new JMenuItem(text)
    item.addActionListener({ ActionEvent e -> action.call() } as ActionListener)
    return item
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Context menu ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Map filter by tag ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// filters the MAP to the nodes carrying the tag (or any of its subtags), opening the
// folded branches to reveal them — and remembering what was opened, to restore on clear
void filterMapByTag(TagRow row) {
    String qn = row.qualifiedName
    String prefix = qn + separator()
    def rootProxy = ProxyFactory.createNode(boundMapView.map.rootNode, null)
    Set<NodeModel> matches = new HashSet<NodeModel>()
    rootProxy.find { n ->
        n.getTags().getTags().any { String t -> t == qn || t.startsWith(prefix) }
    }.each { matches.add((NodeModel) it.delegate) }

    if (matches.isEmpty()) {
        showStatus("No node carries '" + qn + "'")
        return
    }

    MapModel map = boundMapView.getMap()
    ICondition condition = { NodeModel n -> matches.contains(n) } as ICondition
    Filter filter = new Filter(condition, false, true, showTagFilterDescendants, false, null)
    FilterController.getCurrentFilterController().applyFilter(map, true, filter)
    unfoldAncestorsTracking(matches, filter)
    mapFilterActive = true
    showStatus("Map filtered: " + matches.size() + " node" + (matches.size() == 1 ? "" : "s")
            + " with '" + qn + "'")
}

void unfoldAncestorsTracking(Collection<NodeModel> matches, Filter filter) {
    def mapController = Controller.getCurrentModeController().getMapController()
    Set<NodeModel> visited = new HashSet<NodeModel>()
    for (NodeModel match : matches) {
        NodeModel ancestor = match.getParentNode()
        while (ancestor != null) {
            if (visited.add(ancestor) && ancestor.isFolded()) {
                nodesUnfoldedByFilter.add(ancestor)
                mapController.setFolded(ancestor, false, filter)
            }
            ancestor = ancestor.getParentNode()
        }
    }
}

// ⚠️ NOT applyNoFiltering: a raw ICondition never enters the toolbar combo, so that
// path is a no-op (verified in SearchPanel). The NO_FILTERING sentinel clears synchronously.
void clearMapFilter(boolean announce) {
    if (!mapFilterActive && nodesUnfoldedByFilter.isEmpty()) return

    if (boundMapView != null) {
        MapModel map = boundMapView.getMap()
        Filter noFilter = new Filter(FilterController.NO_FILTERING, false, true, showTagFilterDescendants, false, null)
        FilterController.getCurrentFilterController().applyFilter(map, true, noFilter)
        restoreFolding()
    }
    mapFilterActive = false
    if (announce) showStatus("Map filter cleared; folding restored")
}

void restoreFolding() {
    if (nodesUnfoldedByFilter.isEmpty()) return
    def mapController = Controller.getCurrentModeController().getMapController()
    def selection = boundMapView.getMapSelection()
    Filter current = selection != null ? selection.getFilter() : null
    for (NodeModel node : nodesUnfoldedByFilter) {
        if (isNodeInMap(node)) mapController.setFolded(node, true, current)
    }
    nodesUnfoldedByFilter.clear()
}

boolean isNodeInMap(NodeModel node) {
    NodeModel top = node
    while (top.getParentNode() != null) top = top.getParentNode()
    return top.is(boundMapView.getMap().getRootNode())
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Map filter by tag ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Retract / expand ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

int viewportHeight() {
    return boundScrollPane.getViewport().getHeight()
}

int retractedWidth() {
    return (int) (boundScrollPane.getViewport().getWidth() / retractedWidthFactor)
}

int expandedWidth() {
    return retractedWidth() * expandedWidthFactor
}

int wideWidth() {
    return (int) (boundScrollPane.getViewport().getWidth() * wideWidthPercent / 100)
}

int fittedHeight(int panelWidth) {
    invalidatePreferredSizeCache()
    int preferred = (int) tagPanel.getPreferredSize().height

    // The horizontal scroll bar (a long or deeply nested tag overflows the width) is painted
    // INSIDE the tree's scroll pane and eats ~17px off the bottom — which clips the last row
    // and brings up a VERTICAL bar too, with room to spare in the viewport. Reserve its
    // height in advance. Capped by the viewport below: when there is no room left to grow,
    // the vertical bar is legitimate.
    if (horizontalScrollBarNeeded(panelWidth)) {
        preferred += (int) treeScrollPane.getHorizontalScrollBar().getPreferredSize().height
    }
    return Math.min(preferred, viewportHeight())
}

// ⚠️ `Container.getPreferredSize()` returns a CACHED value while the container is VALID —
// it only recomputes when invalid. And `JScrollPane` is a validate root, so `revalidate()`
// inside the tree stops there and NEVER invalidates the panel above it: the panel would be
// measured against the tree it had one refresh ago. MEASURED symptom: panel 390px tall when
// it wanted 398 + 17, two rows clipped and a vertical scroll bar with 912px of room to
// spare. Invalidate the chain by hand before asking anyone their preferred size.
void invalidatePreferredSizeCache() {
    if (tagTree != null) tagTree.invalidate()
    if (treeScrollPane != null) treeScrollPane.invalidate()
    if (favoritesStrip != null) favoritesStrip.invalidate()
    if (tagPanel != null) tagPanel.invalidate()
}

// PREDICTED from the width, never read from the layout: isVisible() on the bar only updates
// after the layout settles, so reserving by it would still show one frame with the spurious
// vertical bar. This is synchronous — does the widest row fit in the usable width?
boolean horizontalScrollBarNeeded(int panelWidth) {
    if (treeScrollPane == null || tagTree == null || tagTree.getRowCount() == 0) return false

    int contentWidth = (int) tagTree.getPreferredSize().width
    // if the rows already overflow the viewport height, the vertical bar WILL appear and
    // steal width from the tree — count it in, or the prediction is off by its width
    boolean verticalLikely = ((int) tagPanel.getPreferredSize().height) > viewportHeight()
    int verticalWidth = verticalLikely ? (int) treeScrollPane.getVerticalScrollBar().getPreferredSize().width : 0
    int availableWidth = panelWidth - 2 * panelBorderThickness - verticalWidth

    return contentWidth > availableWidth
}

boolean panelHasFocus() {
    Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
    return owner != null && tagPanel != null && SwingUtilities.isDescendingFrom(owner, tagPanel)
}

void fitPanelBounds() {
    if (tagPanel == null) return
    boolean stayExpanded = mouseOverPanel || panelHasFocus() || popupOpen ||
            (tagTree != null && tagTree.isEditing())
    int width = wideMode ? wideWidth() : (stayExpanded ? expandedWidth() : retractedWidth())
    animatePanelToWidth(width)
}

void animatePanelToWidth(int targetWidth) {
    if (resizeAnimationTimer != null) {
        resizeAnimationTimer.stop()
        resizeAnimationTimer = null
    }

    int startWidth = tagPanel.getWidth()
    int rowCount = tagTree != null ? tagTree.getRowCount() : 0
    if (resizeAnimationSteps <= 1 || startWidth == targetWidth || rowCount > resizeAnimationMaxRows) {
        applyPanelBounds(targetWidth)
        return
    }

    int[] step = [0]
    resizeAnimationTimer = new Timer(resizeAnimationStepMs, { ActionEvent e ->
        step[0]++
        if (step[0] >= resizeAnimationSteps) {
            ((Timer) e.getSource()).stop()
            resizeAnimationTimer = null
            applyPanelBounds(targetWidth)
        } else {
            float t = step[0] / (float) resizeAnimationSteps
            float eased = 1f - (1f - t) * (1f - t)
            applyPanelBounds(startWidth + (int) ((targetWidth - startWidth) * eased))
        }
    } as ActionListener)
    resizeAnimationTimer.start()
}

// anchored to the TOP-RIGHT edge of the viewport (SearchPanel owns the top-left).
// x is recomputed from the width on every change — the right edge is the fixed one.
void applyPanelBounds(int width) {
    if (tagPanel == null) return
    int height = fittedHeight(width)
    Rectangle viewportBounds = viewportBoundsInHost()
    int x = (viewportBounds.x as int) + (viewportBounds.width as int) - width
    int y = viewportBounds.y as int
    if (tagPanel.getX() == x && tagPanel.getY() == y
            && tagPanel.getWidth() == width && tagPanel.getHeight() == height) return

    tagPanel.setBounds(x, y, width, height)
    overlayHost.revalidate()
    overlayHost.repaint()
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Retract / expand ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Utilities ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void showStatus(String message) {
    if (statusLabel != null) statusLabel.setText(" " + message)
}

JPanel transparentPanel(LayoutManager layout) {
    JPanel panel = new JPanel(layout)
    panel.setOpaque(false)
    return panel
}

Font itemFont() {
    if (cachedItemFont == null) cachedItemFont = new Font(panelTextFontName, Font.PLAIN, panelTextFontSize)
    return cachedItemFont
}

Color mapBackground() {
    return boundMapView.getBackground() ?: Color.WHITE
}

Color barTextColor() {
    return UITools.getTextColorForBackground(barColor)
}

Color panelBorderColor() {
    Color base = UITools.getTextColorForBackground(mapBackground())
    return new Color(base.getRed(), base.getGreen(), base.getBlue(), panelBorderOpacity)
}

Color blendColors(Color base, Color tint, float ratio) {
    return new Color(
            (int) (base.getRed() + (tint.getRed() - base.getRed()) * ratio),
            (int) (base.getGreen() + (tint.getGreen() - base.getGreen()) * ratio),
            (int) (base.getBlue() + (tint.getBlue() - base.getBlue()) * ratio))
}

Color barHoverColor() {
    return blendColors(barColor, barTextColor(), 0.18f)
}

void bindKey(JComponent component, int condition, int keyCode, int modifiers, String actionName, Closure action) {
    component.getInputMap(condition).put(KeyStroke.getKeyStroke(keyCode, modifiers), actionName)
    component.getActionMap().put(actionName, new AbstractAction() {
        @Override
        void actionPerformed(ActionEvent e) { action.call() }
    })
}

void addHoverListenerRecursively(Component component) {
    component.addMouseListener(hoverListener)
    if (component instanceof Container) {
        ((Container) component).components.each { addHoverListenerRecursively(it) }
    }
}

// markers checked against the actual font; missing glyphs get ASCII fallbacks
void pickGlyphs() {
    Font font = itemFont()
    if (font.canDisplayUpTo(markAll) != -1) markAll = "*"
    if (font.canDisplayUpTo(markSome) != -1) markSome = "~"
    if (font.canDisplayUpTo(editSymbol) != -1) editSymbol = "#"
    if (font.canDisplayUpTo(favoriteSymbol) != -1) favoriteSymbol = "!"
    if (font.canDisplayUpTo(filterHidesSymbol) != -1) filterHidesSymbol = "v"
    if (font.canDisplayUpTo(highlightOnlySymbol) != -1) highlightOnlySymbol = "-"
}

// per-character accent folding — same length in and out (see SearchPanel for the contract)
String foldAccents(String text) {
    StringBuilder out = null
    for (int i = 0; i < text.length(); i++) {
        char ch = text.charAt(i)
        char folded = ch < ((char) 128) ? ch : foldChar(ch)
        if (out == null && folded != ch) {
            out = new StringBuilder(text.length())
            out.append(text, 0, i)
        }
        if (out != null) out.append(folded)
    }
    return out == null ? text : out.toString()
}

char foldChar(char ch) {
    Character cached = accentFoldCache.get(ch)
    if (cached != null) return cached.charValue()

    String decomposed = java.text.Normalizer.normalize(String.valueOf(ch), java.text.Normalizer.Form.NFD)
    char base = ch
    for (int j = 0; j < decomposed.length(); j++) {
        if (Character.getType(decomposed.charAt(j)) != Character.NON_SPACING_MARK) {
            base = decomposed.charAt(j)
            break
        }
    }
    accentFoldCache.put(ch, base)
    return base
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Utilities ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/
