// Copyright (c) 2026 euu2021
// SPDX-License-Identifier: GPL-2.0-or-later
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 2 of the License, or
// (at your option) any later version.

/***************************************************************************

 Search Panel — cross-cutting search with lines linking each result to its node.

 A slimmed-down version of the search panel from UtilityPanels.groovy (v1.48), by euu2021:
 https://github.com/euu2021/Freeplane_UtilityPanels

 Kept:
   - the cross-cutting search logic (one term on the node, the rest on its ancestors)
   - the lines linking each result to its corresponding node in the map
   - the retractable panel, which expands on hover

 Replaced/removed:
   - inspectors on hover  ->  tooltip with the ancestor line
   - node highlighters in the map
   - the other panels (breadcrumbs, recents, pinned, tags, styles)
   - search history and JSON persistence

 The panel does NOT follow the active map: it attaches to the MapView it was launched
 on and stays there. Each tab has its own MapViewScrollPane (MapViewSerializer.java:104-113),
 so each map can have its own panel, independent of the others.

 Usage:
   - launch the script      -> opens the panel on the current map; if already open, returns
                              focus to the search field and selects the text
   - ESC (focus on panel)   -> closes the panel
   - » on the title bar     -> TOGGLE: pins the panel wide (50% of the map), ignoring hover
   - ✕ on the title bar     -> closes the panel
   - ⌫ next to the field    -> clears the search
   - click a result         -> goes to the node in the map
   - ↑ / ↓ in the field     -> walks through the results WITHOUT moving in the map;
                              shows the ancestor line in a popup to the right of the panel
   - ENTER                  -> goes to the selected result (the 1st, if none is selected)

 Widths: retracted (viewport/20) -> expanded on hover/focus (x4) -> fixed wide (» , 50%)

 *****************************************************************/

// @ExecutionModes({ON_SINGLE_NODE="/menu_bar/euu"})

import groovy.transform.Field

import org.freeplane.core.ui.components.UITools
import org.freeplane.core.util.HtmlUtils
import org.freeplane.features.map.NodeModel
import org.freeplane.features.map.clipboard.MapClipboardController
import org.freeplane.features.map.mindmapmode.MMapController
import org.freeplane.features.map.mindmapmode.InsertionRelation
import org.freeplane.features.mode.Controller
import org.freeplane.features.nodestyle.NodeStyleController
import org.freeplane.features.styles.LogicalStyleController.StyleOption
import org.freeplane.view.swing.map.MapView
import org.freeplane.view.swing.map.MapViewScrollPane
import org.freeplane.view.swing.map.NodeView

import javax.swing.*
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionListener

import java.awt.*
import java.awt.event.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.*

// after java.awt.*: without this, List becomes java.awt.List, which is not generic
import java.util.List


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ User settings ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

@Field String panelTextFontName = "Dialog"
@Field int panelTextFontSize = 15

// line 2 of each result: the breadcrumb (ancestor path), smaller than line 1
@Field int breadcrumbFontSize = 11

@Field int retractedWidthFactor = 20
@Field int expandedWidthFactor = 4

// the » button on the bar: pins the panel at this fraction of the viewport width, ignoring hover
@Field int wideWidthPercent = 50
@Field String wideOffSymbol = "»"
@Field String wideOnSymbol = "«"

@Field int maxNumberOfResults = 500

@Field int liveSearchDelayMs = 0   // no debounce, by choice: searches on every keystroke. The race
                                   // between in-flight searches is resolved by searchGeneration.
@Field int retractDelayMs = 400

// expand/retract transition: interpolates the width in short steps (ease-out) instead
// of jumping in a single frame. 0 or 1 = no animation
@Field int resizeAnimationSteps = 4
@Field int resizeAnimationStepMs = 15

@Field float lineThickness = 5.5f

// hover/selection on a result: its line thickens and the others fade (low alpha)
@Field float emphasizedLineThickness = 9f
@Field int dimmedLineAlpha = 55   // 0 = invisible, 255 = full

@Field int widthOfTheClearButton = 30

// glyphs verified in the Dialog font (all exist; width in bold 13px):
//   ⌫ 17px erase-left   ⌦ 17px erase-right   ⎌ 9px undo
//   ✕ 11px  ✖ 11px  ✗ 11px  ⊗ 11px  ⨯ 9px  × 8px  X 9px
@Field String clearButtonSymbol = "⌫"
@Field String closeButtonSymbol = "✕"

@Field int titleBarHeight = 24
@Field String titleBarText = "Search"

// shown faded in the empty field; disappears on the first keystroke
@Field String searchFieldPlaceholder = "Type to search…"

// opaque background of the top and bottom bars. the text color comes from here on its own, via
// UITools.getTextColorForBackground (cutoff: luminance > 160 -> black, otherwise white).
//
// ⚠️ Do NOT use medium gray: between #767676 and #a0a0a0 the cutoff still yields WHITE text, but
// there is no longer any contrast. Measured: #808080 -> 3.95:1 and #a0a0a0 -> 2.61:1 (illegible);
// #a1a1a1 crosses the cutoff, turns to black text and jumps to 8.13:1.
// Safe: up to #767676 (white text) or from #a1a1a1 on (black text).
@Field Color barColor = new Color(0x50, 0x50, 0x50)

@Field int panelBorderThickness = 1
// 0 = invisible, 255 = full color (white over a dark map, black over a light map)
@Field int panelBorderOpacity = 150

// the highlighted text is always black over this color, so pick a light one.
// amber instead of #00ff00: pure lime green screamed on every result at once
@Field String highlightColorHex = "#ffd54f"

// arrows between the nodes of the result chain: one per level of distance
@Field String arrowSymbol = "»"

// Red chosen by sweeping, maximizing the WORST contrast among white background,
// #3d3d3d, #1e1e1e and black -- because the item background is the node's (or the map's) and can be
// any of them. Measured: white 3.52:1 · #3d3d3d 3.08:1 · black 5.96:1.
//
// ⚠️ Don't swap it for Jumper's #cc0000: it gives 5.89:1 on white but *1.85:1* on #3d3d3d.
// ⚠️ ~3.1:1 is the CEILING for a single color: 4.5:1 against white requires luminance <=0.18 and
//    against #3d3d3d requires >=0.38 -- impossible. To get past 3.1 you need TWO colors,
//    chosen by the item background (empty here = inherits the text color, always legible).
@Field String arrowColorHex = "#ff3c3c"

@Field int tooltipWidth = 420

// keyboard-navigation tooltip: appears to the right of the panel, so as not to cover it
@Field int tooltipGapPx = 8

// Drag and drop between panel and map.
//  - dragging a result INTO the map: the map receives it as in an internal drag.
//  - dropping a map node ONTO a result: reparents the node as a child of the result.
// ⚠️ ACTION_MOVE reparents for real (destructive). Switch to DnDConstants.ACTION_COPY
// if you want dragging into the map to merely COPY. (UtilityPanels uses MOVE.)
@Field int dragDropAction = DnDConstants.ACTION_MOVE
// highlight of the target cell while a map node hovers over it
@Field Color dropHighlightColor = new Color(0xFF, 0xC1, 0x07)

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ User settings ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


@Field final String PANEL_NAME = "SearchPanel"
@Field final String OVERLAY_NAME = "SearchPanelLines"
@Field final String SEARCH_FIELD_NAME = "SearchPanelField"

// programmatic closer: the round that opens leaves here a closure that sees its own
// state (a fresh execution has empty @Fields and could not reach the listeners nor
// the supplier). Not used by the trigger -- it's a hatch for closing from outside.
@Field final String CLOSE_HANDLE_KEY = "SearchPanelCloseHandle"
// twin of the closer for the width toggle: re-triggering the script with the field already focused
// lands here (a fresh execution has empty @Fields and could not reach wideButton nor fitPanelBounds).
@Field final String TOGGLE_HANDLE_KEY = "SearchPanelToggleHandle"
@Field final String SUPPLIER_KEY = "SearchPanelReservedAreaSupplier"

// Harmonized palettes (Tableau/Material tones) in place of the saturated AWT constants
// (Color.CYAN, Color.MAGENTA... -- which gave an applet look; Color.CYAN.brighter()
// was even a no-op, the channels were already at 255). Similar lightness within
// each palette: dark enough for a light map, light enough for a dark map.
@Field final List<Color> COLORS_FOR_LIGHT_BG = [
        new Color(0x1f, 0x77, 0xb4),   // blue
        new Color(0xd6, 0x27, 0x28),   // red
        new Color(0x2c, 0xa0, 0x2c),   // green
        new Color(0x94, 0x67, 0xbd),   // purple
        new Color(0xe6, 0x51, 0x00),   // orange
        new Color(0x00, 0x83, 0x8f),   // teal
        new Color(0xc2, 0x18, 0x5b),   // magenta
        new Color(0x6d, 0x4c, 0x41),   // brown
]

@Field final List<Color> COLORS_FOR_DARK_BG = [
        new Color(0x4f, 0xc3, 0xf7),   // blue
        new Color(0xef, 0x53, 0x50),   // red
        new Color(0x81, 0xc7, 0x84),   // green
        new Color(0xba, 0x68, 0xc8),   // purple
        new Color(0xff, 0xb7, 0x4d),   // orange
        new Color(0x4d, 0xd0, 0xe1),   // cyan
        new Color(0xf0, 0x62, 0x92),   // pink
        new Color(0xff, 0xf1, 0x76),   // yellow
]

@Field MapView boundMapView
@Field MapViewScrollPane boundScrollPane

@Field JPanel searchPanel
@Field JTextField searchField
@Field JLabel statusLabel
@Field JList<NodeModel> resultsList
@Field JScrollPane resultsScrollPane
@Field DefaultListModel<NodeModel> results = new DefaultListModel<NodeModel>()

@Field JPanel linesOverlay
@Field List<Map<String, Object>> lineData = []
@Field Object reservedAreaSupplier

@Field Timer liveSearchTimer
@Field Timer retractTimer
@Field Timer resizeAnimationTimer
@Field MouseListener hoverListener
@Field ComponentListener mapViewListener
@Field ComponentListener viewportListener

@Field JButton wideButton
@Field boolean wideMode = false

@Field String searchText = ""
@Field String lastSearchText = ""
// the terms of the current search, in lowercase: the renderer builds the chain from them
@Field String[] currentTerms = new String[0]
@Field boolean mouseOverPanel = false

// with live search at 0ms, two searches can be in flight at once and the one that
// FINISHES last would win, even if it's the older one. Each search (and each clear)
// increments the generation; a late done() compares the one it captured against the current
// one and discards itself. The FIX anchors on the EDT accesses (runSearch, clearSearch, done) --
// so an int is enough. doInBackground also reads the field, but only to ABORT EARLY a
// superseded search: reading a stale value off the EDT merely delays the abort; what keeps a
// stale result from winning is still the check in done().
@Field int searchGeneration = 0

// HTML and tooltip of each result, ready-made: the renderer runs on every repaint of every
// cell, so the expensive work (chain, highlight, escape) is done ONCE per search,
// in the SwingWorker. The maps are built off the EDT and swapped wholesale in done() --
// the renderer never reads a map under construction.
@Field Map<NodeModel, String> itemHtmlCache = [:]
@Field Map<NodeModel, String> tooltipCache = [:]

// keyboard-navigation popup. the renderer's native tooltip still applies for the
// mouse; this one exists because a native tooltip is driven by the mouse and can't position
// itself outside the component.
@Field Popup navigationTooltip

@Field Font cachedItemFont
@Field int cachedCellHeight = 0
@Field final Map<Character, Character> accentFoldCache = new java.util.concurrent.ConcurrentHashMap<Character, Character>()
@Field Stroke solidLineStroke
@Field Stroke dashedLineStroke
@Field Stroke emphasizedSolidStroke
@Field Stroke emphasizedDashedStroke

// result currently under the cursor (-1 = none). The active line = this one, or the selection when
// there is no hover; the overlay repaints when it changes. Only affects the EMPHASIS of the lines, no navigation.
@Field int hoveredResultIndex = -1


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Main code ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

boundMapView = Controller.currentController.mapViewManager.mapView as MapView
// getAncestorOfClass is the Freeplane idiom (used everywhere): robust to changes in the
// hierarchy's depth and returns a clean null, vs .parent.parent which counts steps by hand
// and blows up midway. ✅ Verified equivalent to attaching to the scroll pane; hanging on the MapViewPane
// would only gain stacking over the Map Overview (edge case) and its package isn't even exported to scripts.
boundScrollPane = SwingUtilities.getAncestorOfClass(MapViewScrollPane, boundMapView) as MapViewScrollPane

if (focusPanelAlreadyOnThisMap()) return

// CRITICAL ORDER against leaks: the closer goes into the scrollPane BEFORE any
// listener exists. This way, EVERY listener we create below is already covered -- if this
// script dies midway, or we restart, the next round finds the closer and clears the
// listeners by reference. Registering last (as it was before) left the listeners
// orphaned and firing forever on every map movement -- the leak that degraded
// UtilityPanels over time.
boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, { -> closePanel() })
// captures THIS round's @Fields; only called on a later re-invocation, when
// wideButton already exists (createSearchPanel has already run). See focusPanelAlreadyOnThisMap().
boundScrollPane.putClientProperty(TOGGLE_HANDLE_KEY, { -> toggleWideMode() })

liveSearchTimer = new Timer(liveSearchDelayMs, { ActionEvent e -> runSearch() } as ActionListener)
liveSearchTimer.setRepeats(false)

retractTimer = new Timer(retractDelayMs, { ActionEvent e -> fitPanelBounds() } as ActionListener)
retractTimer.setRepeats(false)

createSearchPanel()
createLinesOverlay()
startListeners()

return

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Main code ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Lifecycle ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// Triggering the script with the panel already open does NOT close it: it returns focus to it.
// So the trigger gesture always means "take me to the search", and closing is always ESC (or the ✕).
//
// "Healthy panel" = the component exists by name AND the closer exists in the client property.
// Since the closer is registered BEFORE the listeners, that dual presence guarantees the
// listeners are tracked. A panel by name WITHOUT a closer = leftover from an execution that
// broke; in that case we purge and create a new one, instead of focusing a zombie.
boolean focusPanelAlreadyOnThisMap() {
    JPanel existingPanel = boundScrollPane.components.find { it.name == PANEL_NAME } as JPanel
    boolean hasCloseHandle = boundScrollPane.getClientProperty(CLOSE_HANDLE_KEY) != null

    if (existingPanel != null && hasCloseHandle) {
        JTextField existingField = findByName(existingPanel, SEARCH_FIELD_NAME) as JTextField
        if (existingField != null) {
            if (existingField.isFocusOwner()) {
                // we were already in the search -> this 2nd trigger toggles the width (wide/normal),
                // reusing the open shortcut as a toggle. Focus stays in the field, without touching the text.
                Object toggle = boundScrollPane.getClientProperty(TOGGLE_HANDLE_KEY)
                if (toggle != null) toggle.call()
            } else {
                // we came from the map -> just bring focus back to the field, without touching the width
                existingField.requestFocusInWindow()
                existingField.selectAll()   // typing right away replaces the search
            }
        }
        return true
    }

    // either there is no panel, or there is a zombie without a closer: clear everything reachable and
    // let main go on creating a fresh, clean panel
    purgeSearchPanelArtifacts()
    return false
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

// Cleanup that does NOT depend on this instance's @Fields (they're born empty on each execution).
// Two layers:
//  1. the previous round's closer -- it's a closure that captured THAT round's @Fields,
//     so it removes the listeners and the supplier by reference (the only way to kill an
//     anonymous ComponentListener whose creating instance has already died);
//  2. a defensive pass by name/client property, for the case of a zombie without a closer.
void purgeSearchPanelArtifacts() {
    Object previousCloser = boundScrollPane.getClientProperty(CLOSE_HANDLE_KEY)
    if (previousCloser != null) previousCloser.call()

    boundScrollPane.components
            .findAll { it.name == PANEL_NAME || it.name == OVERLAY_NAME }
            .each { boundScrollPane.remove(it) }

    Object leftoverSupplier = boundScrollPane.getClientProperty(SUPPLIER_KEY)
    if (leftoverSupplier != null) {
        boundScrollPane.removeReservedAreaSupplier(leftoverSupplier)
        boundScrollPane.putClientProperty(SUPPLIER_KEY, null)
    }
    boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, null)
    boundScrollPane.putClientProperty(TOGGLE_HANDLE_KEY, null)

    boundScrollPane.revalidate()
    boundScrollPane.repaint()
}

void closePanel() {
    liveSearchTimer.stop()
    retractTimer.stop()
    if (resizeAnimationTimer != null) {
        resizeAnimationTimer.stop()
        resizeAnimationTimer = null
    }
    // Popup lives in its own window: if not hidden, it outlives the panel
    hideNavigationTooltip()

    boundMapView.removeComponentListener(mapViewListener)
    boundScrollPane.viewport.removeComponentListener(viewportListener)

    if (reservedAreaSupplier != null) {
        boundScrollPane.removeReservedAreaSupplier(reservedAreaSupplier)
        reservedAreaSupplier = null
    }

    if (linesOverlay != null) {
        boundScrollPane.remove(linesOverlay)
        linesOverlay = null
    }
    if (searchPanel != null) {
        boundScrollPane.remove(searchPanel)
        searchPanel = null
    }

    results.clear()
    lineData.clear()

    boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, null)
    boundScrollPane.putClientProperty(TOGGLE_HANDLE_KEY, null)
    boundScrollPane.putClientProperty(SUPPLIER_KEY, null)

    boundScrollPane.revalidate()
    boundScrollPane.repaint()
    boundMapView.requestFocusInWindow()
}

void startListeners() {
    // direct refresh, WITHOUT debounce: Timer.restart() on every event never fires during
    // a continuous pan (only when it pauses) and the lines looked frozen. The per-event
    // recompute is cheap enough even with hundreds of results — measured by the user.
    mapViewListener = new ComponentAdapter() {
        @Override
        void componentMoved(ComponentEvent e) { refreshLines() }

        @Override
        void componentResized(ComponentEvent e) { refreshLines() }
    }
    boundMapView.addComponentListener(mapViewListener)

    viewportListener = new ComponentAdapter() {
        @Override
        void componentResized(ComponentEvent e) { fitPanelBounds() }
    }
    boundScrollPane.viewport.addComponentListener(viewportListener)
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Lifecycle ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Panel ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void createSearchPanel() {
    searchPanel = transparentPanel(new BorderLayout())
    searchPanel.setName(PANEL_NAME)
    // the border goes into the panel's insets, so fittedHeight() already accounts for it on its own
    // (getPreferredSize includes insets). alpha blends with the map: the panel is non-opaque and
    // is painted after the viewport.
    searchPanel.setBorder(BorderFactory.createLineBorder(panelBorderColor(), panelBorderThickness))

    JPanel header = transparentPanel(new BorderLayout())
    header.add(createTitleBar(), BorderLayout.NORTH)
    header.add(createSearchBox(), BorderLayout.CENTER)

    searchPanel.add(header, BorderLayout.NORTH)
    searchPanel.add(createResultsArea(), BorderLayout.CENTER)

    // ESC closes, and it applies to focus on ANY descendant (field, bar buttons) --
    // hence WHEN_ANCESTOR_OF_FOCUSED_COMPONENT on the panel, and not WHEN_FOCUSED on the field.
    bindKey(searchPanel, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            KeyEvent.VK_ESCAPE, "closeSearchPanel", { closePanel() })

    searchPanel.setBounds(0, 0, retractedWidth(), fittedHeight(retractedWidth()))

    boundScrollPane.add(searchPanel)
    // add() puts the component BEHIND the viewport; z-order 0 = painted last = in front
    boundScrollPane.setComponentZOrder(searchPanel, 0)

    reservedAreaSupplier = { ->
        searchPanel != null && searchPanel.isVisible() ? searchPanel.getBounds() : MapViewScrollPane.EMPTY_RECTANGLE
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
            // leaving a component drops the exposed native tooltip; without this, leaving the
            // list OVER the tooltip left it hanging over the map (see the method)
            dismissNativeTooltip()
        }
    }
    addHoverListenerRecursively(searchPanel)

    boundScrollPane.revalidate()
    boundScrollPane.repaint()
    searchField.requestFocusInWindow()
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

    wideButton = createBarButton(wideOffSymbol, barForeground, wideTooltip(), { toggleWideMode() })

    JButton closeButton = createBarButton(closeButtonSymbol, barForeground, "Close the panel", { closePanel() })
    closeButton.addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) { closeButton.setForeground(Color.RED) }

        @Override
        void mouseExited(MouseEvent e) { closeButton.setForeground(barForeground) }
    })

    JPanel barButtons = transparentPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0))
    barButtons.add(wideButton)
    barButtons.add(closeButton)

    titleBar.add(title, BorderLayout.CENTER)
    titleBar.add(barButtons, BorderLayout.EAST)
    return titleBar
}

JButton createBarButton(String symbol, Color barForeground, String tooltip, Closure action) {
    JButton button = new JButton(symbol)
    // explicit font: the L&F default here is Tahoma 22px, and the glyph would not fit
    button.setFont(new Font(panelTextFontName, Font.BOLD, panelTextFontSize - 2))
    button.setForeground(barForeground)
    button.setToolTipText(tooltip)
    button.setPreferredSize(new Dimension(titleBarHeight, titleBarHeight))
    button.setOpaque(false)
    button.setContentAreaFilled(false)
    button.setBorderPainted(false)
    button.setFocusPainted(false)
    // without this the glyph becomes "...": the L&F default margin ([2,14,2,14]) leaves NEGATIVE
    // usable width on a narrow button, and setBorderPainted(false) does not touch the insets.
    button.setMargin(new Insets(0, 0, 0, 0))
    // hover feedback: background one step lighter (the white foreground has nowhere to
    // lighten). contentAreaFilled stays false ALWAYS -- with true the L&F would paint its
    // themed body over our background; opaque(true) alone paints the flat color.
    Color hoverBackground = barHoverColor()
    button.addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) {
            button.setOpaque(true)
            button.setBackground(hoverBackground)
            button.repaint()
        }

        @Override
        void mouseExited(MouseEvent e) {
            button.setOpaque(false)
            button.repaint()
        }
    })
    button.addActionListener({ ActionEvent e -> action.call() } as ActionListener)
    return button
}

String wideTooltip() {
    return wideMode ? "Restore the normal width" : "Expand to " + wideWidthPercent + "% of the map and pin"
}

// while on, the width ignores hover and focus -- fitPanelBounds() consults wideMode first.
//
// ⚠️ Do NOT extract this into a set*(arg) method: a method named setWideMode(x) IS the setter
// of the wideMode property in Groovy, so "setWideMode(true)" becomes the field write
// "wideMode = true" and the method BODY is ignored -- the field changes, but the resize and the
// button text never run, without throwing an exception. It cost a whole investigation. Keep it inline.
void toggleWideMode() {
    wideMode = !wideMode
    wideButton.setText(wideMode ? wideOnSymbol : wideOffSymbol)
    wideButton.setToolTipText(wideTooltip())
    fitPanelBounds()
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

// linear blend base→tint; ratio 0 = base, 1 = tint
Color blendColors(Color base, Color tint, float ratio) {
    return new Color(
            (int) (base.getRed() + (tint.getRed() - base.getRed()) * ratio),
            (int) (base.getGreen() + (tint.getGreen() - base.getGreen()) * ratio),
            (int) (base.getBlue() + (tint.getBlue() - base.getBlue()) * ratio))
}

// bar hover: one step toward the text color (lightens a dark bar, darkens a light bar).
// 0.18 starting from #505050 gives ~#6f6f6f -- still short of the forbidden band
// #767676-#a0a0a0 where white text loses contrast (see the barColor warning).
Color barHoverColor() {
    return blendColors(barColor, barTextColor(), 0.18f)
}

JPanel createSearchBox() {
    searchField = new JTextField() {
        // placeholder painted by hand (Swing has no native one). Appears whenever the field is
        // empty -- even when focused: the field gains focus right at opening, and hiding it on focus
        // would mean no one ever sees it. Disappears on the first keystroke (the repaint comes from the document).
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g)
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create()
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.setFont(getFont().deriveFont(Font.ITALIC))
                    g2.setColor(blendColors(getForeground(), getBackground(), 0.55f))
                    g2.drawString(searchFieldPlaceholder, getInsets().left,
                            getInsets().top + g2.getFontMetrics().getAscent())
                } finally {
                    g2.dispose()
                }
            }
        }
    }
    // it's by this name that a fresh execution finds the field again to return focus
    searchField.setName(SEARCH_FIELD_NAME)
    searchField.setFont(itemFont())
    // inner padding: without this the text (and the placeholder) stick to the L&F border
    searchField.setBorder(BorderFactory.createCompoundBorder(
            searchField.getBorder(), BorderFactory.createEmptyBorder(2, 6, 2, 6)))

    searchField.getDocument().addDocumentListener(new DocumentListener() {
        @Override
        void insertUpdate(DocumentEvent e) { liveSearchTimer.restart() }

        @Override
        void removeUpdate(DocumentEvent e) { liveSearchTimer.restart() }

        @Override
        void changedUpdate(DocumentEvent e) { liveSearchTimer.restart() }
    })

    searchField.addFocusListener(new FocusAdapter() {
        @Override
        void focusGained(FocusEvent e) { fitPanelBounds() }

        @Override
        void focusLost(FocusEvent e) {
            // the popup is positioned from the panel; if it's going to retract, the spot changes
            hideNavigationTooltip()
            retractTimer.restart()
        }
    })

    // the arrows walk through the results WITHOUT navigating; only ENTER navigates. They live on the
    // search field (and not on the list) because that's where focus is while typing -- it's the
    // autocomplete pattern, and the list is setFocusable(false) so focus never leaves here.
    bindKey(searchField, KeyEvent.VK_DOWN, "nextResult", { moveResultSelection(1) })
    bindKey(searchField, KeyEvent.VK_UP, "previousResult", { moveResultSelection(-1) })
    bindKey(searchField, KeyEvent.VK_ENTER, "goToResult", { goToSelectedResult() })

    JButton clearButton = new JButton(clearButtonSymbol)
    clearButton.setToolTipText("Clear the search")
    // without an explicit font the button inherits the L&F's (Tahoma 22px here, scaled by FP) and
    // the glyph does not fit in the 30px -> becomes "..."
    clearButton.setFont(itemFont())
    clearButton.setPreferredSize(new Dimension(widthOfTheClearButton, 1))
    // same visual language as the title bar (flat in the bar color), in place of the
    // black-on-white with etched relief, which clashed with the flat » and ✕ buttons.
    // EMPTY border instead of setBorderPainted(false): hiding the border keeps its insets
    // and the glyph would become "..." (same trap as in createBarButton).
    // contentAreaFilled(false) BEFORE setOpaque(true): the L&F reinstalls opaque from
    // contentAreaFilled when the order is reversed.
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
    clearButton.addActionListener({ ActionEvent e -> clearSearch() } as ActionListener)

    JPanel searchBox = transparentPanel(new BorderLayout())
    searchBox.add(searchField, BorderLayout.CENTER)
    searchBox.add(clearButton, BorderLayout.EAST)
    return searchBox
}

JPanel createResultsArea() {
    // the default getPreferredScrollableViewportSize uses visibleRowCount (8) × cell height,
    // not the list's real total. By returning the preferredSize (all cells), the panel
    // fits the whole list -- capped by the viewport in fittedHeight. Verified: without
    // this, the panel clipped results with room to spare. (Cells today have a FIXED
    // size -- see fixedCellHeightPx -- so the preferredSize is n×height, without measuring a cell.)
    resultsList = new JList<NodeModel>(results) {
        @Override
        Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize()
        }
    }
    resultsList.setFont(itemFont())
    resultsList.setOpaque(false)
    resultsList.setCellRenderer(createResultRenderer())
    // focus stays always on the search field: this way the arrows reach it while typing,
    // and the list needs no bindings of its own competing with its native ones.
    resultsList.setFocusable(false)

    // BEFORE this was in a ListSelectionListener, which made ANY selection navigate --
    // including the arrows'. Navigating is a decision of the click and of ENTER, not of the selection.
    resultsList.addMouseListener(new MouseAdapter() {
        @Override
        void mouseClicked(MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e)) return
            int index = resultsList.locationToIndex(e.getPoint())
            if (index < 0 || index >= results.size()) return
            Rectangle cell = resultsList.getCellBounds(index, index)
            if (cell == null || !cell.contains(e.getPoint())) return

            resultsList.setSelectedIndex(index)
            hideNavigationTooltip()
            selectAndCenter(results.getElementAt(index))
        }
    })

    configureDragAndDrop()

    // hover on a result -> highlights its line. Only tracks the index and repaints the overlay;
    // does not navigate nor select. mouseExited (the list has no children) resets it on leaving.
    resultsList.addMouseMotionListener(new MouseMotionAdapter() {
        @Override
        void mouseMoved(MouseEvent e) { updateHoveredResult(cellIndexAt(e.getPoint())) }
        @Override
        void mouseDragged(MouseEvent e) { updateHoveredResult(cellIndexAt(e.getPoint())) }
    })
    resultsList.addMouseListener(new MouseAdapter() {
        @Override
        void mouseExited(MouseEvent e) { updateHoveredResult(-1) }
    })
    // without hover, the active line follows the SELECTION (arrows, click). This listener only repaints the
    // overlay -- it does not navigate (it was for navigating that the old ListSelectionListener was removed).
    resultsList.addListSelectionListener({ if (linesOverlay != null) linesOverlay.repaint() } as ListSelectionListener)

    resultsScrollPane = new JScrollPane(resultsList)
    resultsScrollPane.setOpaque(false)
    resultsScrollPane.getViewport().setOpaque(false)
    resultsScrollPane.setBorder(BorderFactory.createEmptyBorder())
    resultsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED)
    resultsScrollPane.getViewport().addChangeListener({ ChangeEvent e -> refreshLines() } as ChangeListener)

    statusLabel = new JLabel(" ")
    statusLabel.setHorizontalAlignment(SwingConstants.CENTER)
    statusLabel.setFont(itemFont())
    statusLabel.setOpaque(true)
    statusLabel.setBackground(barColor)
    statusLabel.setForeground(barTextColor())

    JPanel resultsArea = transparentPanel(new BorderLayout())
    resultsArea.add(resultsScrollPane, BorderLayout.CENTER)
    resultsArea.add(statusLabel, BorderLayout.SOUTH)
    return resultsArea
}

// DnD in both directions. It all lives on resultsList, which is recreated on each opening and removed
// together with the panel in closePanel -> the DragGestureRecognizer and the DropTarget die with the
// list (both form a cycle with it alone), without leaking to MapView/scrollPane. That's why it needs
// no explicit cleanup, unlike the listeners tracked by CLOSE_HANDLE.
void configureDragAndDrop() {
    DragSource dragSource = DragSource.getDefaultDragSource()

    // PANEL -> MAP: dragging a result produces the canonical clipboard transferable, which
    // the map receives on drop just like an internal drag (moves/copies the node at the drop spot).
    dragSource.createDefaultDragGestureRecognizer(resultsList, dragDropAction, { DragGestureEvent dge ->
        NodeModel node = nodeAtPoint(dge.getDragOrigin())
        if (node == null || !isNodeInMap(node)) return   // a dead result does not drag

        // copy(Collection) returns MindMapNodesSelection directly -> avoids UtilityPanels'
        // IMapSelection stub. setDropAction matches the gesture (move vs copy).
        def transferable = MapClipboardController.getController().copy([node])
        // ⚠️ CRUCIAL: copy() does NOT attach the live nodes, so the Collection flavor is missing
        // (mindMapNodeObjectsFlavor). Without it the map does not enter the isLocalNodeMove branch
        // (MNodeDropListener) and falls into paste = COPIES instead of moving; and our own
        // drop-handler (which reads that flavor) finds nothing in list->list. setNodeObjects
        // with selectionContainsSingleNodes=false exposes precisely the flavor both check.
        transferable.setNodeObjects([node], false)
        transferable.setDropAction(dragDropAction)

        Cursor cursor = dragDropAction == DnDConstants.ACTION_COPY ? DragSource.DefaultCopyDrop : DragSource.DefaultMoveDrop
        dragSource.startDrag(dge, cursor, transferable, new DragSourceAdapter() {})
    } as DragGestureListener)

    // MAP -> PANEL: dropping a map node onto a result reparents the node as its child.
    new DropTarget(resultsList, DnDConstants.ACTION_COPY_OR_MOVE, new DropTargetAdapter() {
        @Override
        void dragOver(DropTargetDragEvent dtde) {
            resultsList.putClientProperty("dropTargetIndex", cellIndexAt(dtde.getLocation()))
            resultsList.repaint()
        }

        @Override
        void dragExit(DropTargetEvent dte) {
            resultsList.putClientProperty("dropTargetIndex", -1)
            resultsList.repaint()
        }

        @Override
        void drop(DropTargetDropEvent dtde) {
            resultsList.putClientProperty("dropTargetIndex", -1)
            resultsList.repaint()

            NodeModel target = nodeAtPoint(dtde.getLocation())
            // the map delivers the dragged nodes in the Collection flavor (live nodes)
            DataFlavor nodesFlavor = new DataFlavor("application/freeplane-nodes; class=java.util.Collection", "application/freeplane-nodes")
            // dead target rejected: reparenting into a deleted subtree would lose the node
            if (target == null || !isNodeInMap(target) || !dtde.isDataFlavorSupported(nodesFlavor)) {
                dtde.rejectDrop()
                return
            }

            try {
                dtde.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE)
                Object data = dtde.getTransferable().getTransferData(nodesFlavor)

                List<NodeModel> dragged = []
                if (data instanceof Collection) {
                    data.each { if (it instanceof NodeModel) dragged.add((NodeModel) it) }
                }

                // dropping onto the node itself (or nothing) does not move -- avoids a trivial/invalid reparent
                if (dragged.isEmpty() || dragged.contains(target)) {
                    dtde.dropComplete(true)
                    return
                }

                MMapController mapController = (MMapController) Controller.getCurrentModeController().getMapController()
                mapController.moveNodes(dragged, target, InsertionRelation.AS_CHILD)
                dtde.dropComplete(true)
            } catch (Exception e) {
                e.printStackTrace()
                dtde.dropComplete(false)
            }
        }
    })
}

// index of the cell EXACTLY under the point (locationToIndex alone clamps to the nearest,
// so dropping below the last row would return the last -- here it requires containing the point).
int cellIndexAt(Point point) {
    int index = resultsList.locationToIndex(point)
    if (index < 0 || index >= results.size()) return -1
    Rectangle cell = resultsList.getCellBounds(index, index)
    return (cell != null && cell.contains(point)) ? index : -1
}

NodeModel nodeAtPoint(Point point) {
    int index = cellIndexAt(point)
    return index < 0 ? null : results.getElementAt(index)
}

void updateHoveredResult(int index) {
    if (index == hoveredResultIndex) return
    hoveredResultIndex = index
    if (linesOverlay != null) linesOverlay.repaint()
}

// index of the line to emphasize: hover wins; without hover, the selection. -1 = nothing (all normal).
int activeLineIndex() {
    if (hoveredResultIndex >= 0) return hoveredResultIndex
    return resultsList != null ? resultsList.getSelectedIndex() : -1
}

void bindKey(JComponent component, int keyCode, String actionName, Closure action) {
    bindKey(component, JComponent.WHEN_FOCUSED, keyCode, actionName, action)
}

void bindKey(JComponent component, int condition, int keyCode, String actionName, Closure action) {
    component.getInputMap(condition).put(KeyStroke.getKeyStroke(keyCode, 0), actionName)
    component.getActionMap().put(actionName, new AbstractAction() {
        @Override
        void actionPerformed(ActionEvent e) { action.call() }
    })
}

// walks through the results without touching the map
void moveResultSelection(int delta) {
    int size = results.size()
    if (size == 0) return

    int current = resultsList.getSelectedIndex()
    int next = current < 0 ? (delta > 0 ? 0 : size - 1)
                           : Math.max(0, Math.min(size - 1, current + delta))

    resultsList.setSelectedIndex(next)
    // scroll BEFORE positioning the popup: the cell's y on screen depends on the scroll
    resultsList.ensureIndexIsVisible(next)
    showNavigationTooltip(next)
}

// the only keyboard path that touches the map
void goToSelectedResult() {
    if (results.size() == 0) return

    int index = resultsList.getSelectedIndex()
    if (index < 0) {
        index = 0
        resultsList.setSelectedIndex(index)
        resultsList.ensureIndexIsVisible(index)
    }

    hideNavigationTooltip()
    selectAndCenter(results.getElementAt(index))
}

// a native tooltip is driven by the mouse and appears where the cursor is -- on top of the panel.
// This one is a Popup positioned by hand, flush against the panel's RIGHT edge and aligned with
// the selected line.
void showNavigationTooltip(int index) {
    hideNavigationTooltip()
    if (searchPanel == null || index < 0 || index >= results.size()) return

    NodeModel node = results.getElementAt(index)
    String text = tooltipCache.get(node) ?: ancestorsTooltip(node)
    if (text == null || text.isEmpty()) return

    Rectangle cell = resultsList.getCellBounds(index, index)
    if (cell == null) return

    JToolTip tip = new JToolTip()
    tip.setComponent(resultsList)
    tip.setTipText(text)

    // cell.y is DOUBLE here: Groovy prefers Rectangle.getY() (the Rectangle2D contract) over the
    // int field y. Without the cast there is no Point(int, double). (UtilityPanels solves the
    // same problem with .@y, direct field access)
    Point anchor = new Point(0, cell.y as int)
    UITools.convertPointToAncestor(resultsList, anchor, boundScrollPane)
    anchor.x = searchPanel.getX() + searchPanel.getWidth() + tooltipGapPx
    SwingUtilities.convertPointToScreen(anchor, boundScrollPane)

    // likewise: anchor.x/.y read Point.getX()/getY(), which are DOUBLE (the Point2D contract).
    // (writing anchor.x above lands on the int field -- the asymmetry is Groovy's, not mine)
    Point placed = placeWithinScreen(anchor.x as int, anchor.y as int, tip.getPreferredSize())

    navigationTooltip = PopupFactory.getSharedInstance()
            .getPopup(boundScrollPane, tip, placed.x as int, placed.y as int)
    navigationTooltip.show()
}

// Pushes the tooltip inside the screen instead of leaving it clipped. Near the bottom
// edge it rises just enough to fit entirely (and does not jump above the line: this way
// it stays as close to it as possible). Likewise on the right, if the wide panel leaves no room.
Point placeWithinScreen(int x, int y, Dimension size) {
    Rectangle screen = usableScreenBounds()

    int maxX = screen.x as int + (screen.width as int) - size.width
    int maxY = screen.y as int + (screen.height as int) - size.height

    return new Point(
            Math.max(screen.x as int, Math.min(x, maxX)),
            Math.max(screen.y as int, Math.min(y, maxY)))
}

// the screen where THIS panel is (the user has multiple monitors), minus the OS
// reservations -- taskbar etc.
Rectangle usableScreenBounds() {
    GraphicsConfiguration config = boundScrollPane.getGraphicsConfiguration()
    Rectangle screen = config.getBounds()
    Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config)

    return new Rectangle(
            (screen.x as int) + insets.left,
            (screen.y as int) + insets.top,
            (screen.width as int) - insets.left - insets.right,
            (screen.height as int) - insets.top - insets.bottom)
}

void hideNavigationTooltip() {
    if (navigationTooltip != null) {
        navigationTooltip.hide()
        navigationTooltip = null
    }
}

// The native tooltip (from hover) does not die on its own when the mouse leaves OVER it: the
// heavyweight popup swallows the mouseExited and the ToolTipManager loses track -- the tooltip
// stays hanging over the map until a click. Toggling the manager off/on hides the current
// tooltip right away (setEnabled(false) calls hideTipWindow) without affecting the next ones.
void dismissNativeTooltip() {
    ToolTipManager manager = ToolTipManager.sharedInstance()
    manager.setEnabled(false)
    manager.setEnabled(true)
}

// selectAsTheOnlyOneSelected only scrolls until the node is VISIBLE (MapView.java:2748 ->
// mapScroller.scrollNodeToVisible), which usually leaves it stuck to an edge.
void selectAndCenter(NodeModel node) {
    // the node may have died with the panel open (delete, undo of insert) -- navigating to
    // an orphan blows up inside the MapView. Activation is the single checkpoint.
    if (!isNodeInMap(node)) {
        pruneDeadResults()
        return
    }
    def selection = boundMapView.getMapSelection()
    selection.selectAsTheOnlyOneSelected(node)
    selection.scrollNodeToCenter(node)
}

// "alive" = walking up via getParentNode() reaches the MAP's root (not the view's: a node outside
// the jump-in is still alive). Delete nulls the parent of the removed node (NodeModel.java:508-513),
// so the top of a deleted subtree has a null parent without being the root. (isAttached()
// would answer directly, but it's package-private -- NodeModel.java:633.)
boolean isNodeInMap(NodeModel node) {
    NodeModel top = node
    while (top.getParentNode() != null) top = top.getParentNode()
    return top.is(boundMapView.getMap().getRootNode())
}

// No map listener, ON PURPOSE: a listener on the MapController would outlive the
// TAB closing (closePanel does not run in that case) and would leak. Instead, activating
// a dead result prunes ALL the dead ones at once. A ghost cell is inert until then:
// its line already disappears on its own (addLineData requires the view root in pathToRoot), and the
// renderer only reads caches. Accepted price: the per-index colors shift after the prune.
void pruneDeadResults() {
    for (int i = results.size() - 1; i >= 0; i--) {
        if (!isNodeInMap(results.getElementAt(i))) results.remove(i)
    }
    statusLabel.setText(results.size() + " results. Stale nodes removed.")
    hideNavigationTooltip()
    fitPanelBounds()
    refreshLines()
}

ListCellRenderer<NodeModel> createResultRenderer() {
    return new DefaultListCellRenderer() {
        @Override
        Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (!(value instanceof NodeModel)) return label

            NodeModel node = (NodeModel) value

            label.setFont(itemFont())
            label.setOpaque(true)
            // cells have a FIXED height of 2 lines (see fixedCellHeightPx); a 1-line
            // result (rare) aligns to the top instead of floating centered in the cell
            label.setVerticalAlignment(SwingConstants.TOP)

            // the fallbacks must AGREE: independent fixed white/black defaults gave
            // black over a dark background (node with a defined bg and no text color) and vice versa.
            // a node without a bg appears over the map -- that's the color the text must be legible against.
            Color backgroundColor = NodeStyleController.getController().getBackgroundColor(node, StyleOption.FOR_UNSELECTED_NODE)
                    ?: mapBackground()
            Color fontColor = NodeStyleController.getController().getColor(node, StyleOption.FOR_UNSELECTED_NODE)
                    ?: UITools.getTextColorForBackground(backgroundColor)
            label.setBackground(backgroundColor)
            label.setForeground(fontColor)

            if (isSelected) {
                // integrated selection: tints the cell background with the color of the result's OWN
                // connection line, instead of the L&F blue, which fought with the colored frame and
                // with the node's background. The text becomes legible again against the tinted background.
                Color tinted = blendColors(backgroundColor, colorForIndex(index), 0.35f)
                label.setBackground(tinted)
                label.setForeground(UITools.getTextColorForBackground(tinted))
            }

            // cell under the cursor during a drop coming from the map (see configureDragAndDrop)
            Object dropIndex = list.getClientProperty("dropTargetIndex")
            if (dropIndex instanceof Integer && ((Integer) dropIndex) == index) {
                label.setBackground(dropHighlightColor)
                label.setForeground(UITools.getTextColorForBackground(dropHighlightColor))
            }

            label.setBorder(BorderFactory.createMatteBorder(2, 8, 2, 8, colorForIndex(index)))
            // ready since the search; the fallback only covers a repaint between cache swaps
            String html = itemHtmlCache.get(node)
            label.setText(html != null ? html : resultItemHtml(node, currentTerms))
            String tooltip = tooltipCache.get(node)
            label.setToolTipText(tooltip != null ? tooltip : ancestorsTooltip(node))
            return label
        }
    }
}

String ancestorsTooltip(NodeModel node) {
    List<String> ancestors = []
    NodeModel[] path = node.getPathToRoot()
    for (int i = 0; i < path.length - 1; i++) {
        ancestors.add(escapedPlainTextOf(path[i]))
    }

    String ownText = "<b>" + escapedPlainTextOf(node) + "</b>"
    String line = ancestors.isEmpty() ? ownText : ancestors.join(" &gt; ") + "<br>" + ownText

    return "<html><p width=\"" + tooltipWidth + "\">" + line + "</p></html>"
}

String escapedPlainTextOf(NodeModel node) {
    return HtmlUtils.toXMLEscapedText(HtmlUtils.htmlToPlain(node.getText() ?: ""))
}

// The item shows the CHAIN that satisfied the search, Jumper-style:
//
//     aaa  »»  ccc          (»» = ccc is 2 levels below aaa)
//
// The chain includes the result node and every ancestor that contributes a term the node
// does not have directly. The arrows between two shown = the depth difference between them.
String resultItemHtml(NodeModel node, String[] terms) {
    // line 1: the chain that matched (Jumper-style). line 2: the full breadcrumb. the JList
    // measures each cell, so the double height propagates on its own up to the panel's fittedHeight.
    StringBuilder firstLine = new StringBuilder()
    if (terms.length == 0) {
        firstLine.append(escapedPlainTextOf(node))
    } else {
        NodeModel[] path = node.getPathToRoot()
        int nodeIndex = path.length - 1

        String nodeText = searchableTextOf(node)
        List<String> termsFromAncestors = terms.findAll { !nodeText.contains(it) }

        List<Integer> chain = []
        for (int i = 0; i < nodeIndex; i++) {
            String ancestorText = searchableTextOf(path[i])
            if (termsFromAncestors.any { ancestorText.contains(it) }) chain.add(i)
        }
        chain.add(nodeIndex)

        int previousIndex = -1
        chain.each { int index ->
            if (previousIndex >= 0) firstLine.append(arrowsFor(index - previousIndex))
            firstLine.append(highlightedFragmentOf(path[index], terms))
            previousIndex = index
        }
    }

    StringBuilder html = new StringBuilder("<html>").append(firstLine)
    String breadcrumb = ancestorsBreadcrumbHtml(node)
    if (!breadcrumb.isEmpty()) html.append("<br>").append(breadcrumb)
    return html.append("</html>").toString()
}

// line 2 of the item: the full ancestor path (a › b › c), smaller and italic. Without
// a fixed color -> inherits the item's, which was already matched to the node's background -> legible on any
// node (fixing gray would break on nodes with a light/dark bg, as the arrow sweep already taught).
// The root (no ancestors) gets a single line.
String ancestorsBreadcrumbHtml(NodeModel node) {
    NodeModel[] path = node.getPathToRoot()
    if (path.length <= 1) return ""

    List<String> ancestors = []
    for (int i = 0; i < path.length - 1; i++) {
        ancestors.add(escapedPlainTextOf(path[i]))
    }
    return '<span style="font-size:' + breadcrumbFontSize + 'px;"><i>' + ancestors.join(" › ") + '</i></span>'
}

String arrowsFor(int levels) {
    String glyphs = arrowSymbol * Math.max(1, levels)
    // empty color = inherits the item's, which was already matched to the background -> legible by construction
    String style = arrowColorHex.isEmpty() ? "" : ' style="color:' + arrowColorHex + ';"'
    return " <b" + style + ">" + glyphs + "</b> "
}

// ⚠️ NEVER go back to highlighting with sequential replace over the string that already has markup.
// It was like that (inherited from UtilityPanels) and it broke: searching "gama a", the 1st term wrapped
// "Gama" in a <span style="background-color:...">, and the 2nd term ("a") matched the "a"s
// INSIDE the tag -- spa[n], b[a]ckground -- shredding the markup, which leaked out as text.
// Even with a single term there was the twin of the bug: escaping before highlighting makes "gt" match
// inside "&gt;".
//
// Here the regex never sees markup: it finds the positions in the PLAIN text, merges the overlaps,
// and builds the output once, escaping each piece in isolation.
String highlightedFragmentOf(NodeModel node, String[] terms) {
    String plain = HtmlUtils.htmlToPlain(node.getText() ?: "")
    List<int[]> ranges = mergedMatchRanges(plain, terms)
    if (ranges.isEmpty()) return HtmlUtils.toXMLEscapedText(plain)

    // the green is fixed, so the text color has to be too: without this the highlighted
    // span inherits the node's font (white on a dark map) and disappears into the light green
    String openTag = '<span style="background-color:' + highlightColorHex + '; color:#000000;">'

    StringBuilder fragment = new StringBuilder()
    int cursor = 0
    ranges.each { int[] range ->
        fragment.append(HtmlUtils.toXMLEscapedText(plain.substring(cursor, range[0])))
        fragment.append(openTag)
        fragment.append(HtmlUtils.toXMLEscapedText(plain.substring(range[0], range[1])))
        fragment.append('</span>')
        cursor = range[1]
    }
    fragment.append(HtmlUtils.toXMLEscapedText(plain.substring(cursor)))
    return fragment.toString()
}

// all occurrences of all terms, sorted and with overlaps merged
// (searching "gama a" in "Gama" finds [0,4] and [1,2] and [3,4] -> a single [0,4])
List<int[]> mergedMatchRanges(String text, String[] terms) {
    // the folded text has the SAME length as the original (the foldAccents contract), so the
    // ranges found here paint the original text directly -- searching "coracao" highlights
    // "coração" entirely, cedilla and tilde included
    String haystack = foldAccents(text.toLowerCase())

    List<int[]> found = []
    for (String term : terms) {
        if (term.isEmpty()) continue
        String needle = term.toLowerCase()
        int at = haystack.indexOf(needle)
        while (at >= 0) {
            found.add([at, at + needle.length()] as int[])
            at = haystack.indexOf(needle, at + 1)
        }
    }
    if (found.isEmpty()) return found

    found.sort { it[0] }

    List<int[]> merged = []
    int[] current = found[0]
    for (int i = 1; i < found.size(); i++) {
        int[] next = found[i]
        if (next[0] <= current[1]) {
            if (next[1] > current[1]) current[1] = next[1]
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged
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

// ⚠️ NEVER go back to letting the JList measure the cells: with variable height it calls the
// renderer on all 500 cells to compute the layout, and each call builds an HTML view
// (~19 ms with many highlight <span>s) -> ~10 s PER SEARCH, measured on the CMSP map with
// "cpi c". With fixedCellHeight AND fixedCellWidth (you need BOTH) the JList measures nothing.
//
// A single height for all: derived from a 2-line prototype cell. A root result
// (1 line, rare) sits in a 2-line cell, centered -- the price of skipping measurement.
int fixedCellHeightPx() {
    if (cachedCellHeight == 0) {
        JLabel probe = new JLabel('<html>Ag<br><span style="font-size:' + breadcrumbFontSize + 'px;"><i>Ag</i></span></html>')
        probe.setFont(itemFont())
        probe.setBorder(BorderFactory.createMatteBorder(2, 8, 2, 8, Color.BLACK))
        cachedCellHeight = probe.getPreferredSize().height
    }
    return cachedCellHeight
}

// The width (which feeds the horizontal bar) is PREDICTED over the plain text, off the
// EDT. It measures everything in bold: slightly overestimating only stretches the scroll range a bit;
// underestimating would clip the end of the widest line.
//
// ⚠️ Measuring ALL lines does not scale: text with emoji (surrogate pair) takes
// FontMetrics.stringWidth off the fast path and into the full layout -- and this map has
// emoji everywhere (~1 s per search over the ~1000 lines, measured; getStringBounds, likewise).
// So: it measures only the TOP_K longest in CHARACTERS (counting is free) -- the widest in
// pixels is among them, with a huge statistical margin among 32 candidates.
int estimatedCellWidth(Collection<String> htmls) {
    final int TOP_K = 32
    JLabel probe = new JLabel()
    FontMetrics line1Metrics = probe.getFontMetrics(itemFont().deriveFont(Font.BOLD))
    FontMetrics crumbMetrics = probe.getFontMetrics(itemFont().deriveFont(Font.ITALIC, (float) breadcrumbFontSize))

    List<Object[]> lines = []
    htmls.each { String html ->
        String[] parts = html.replace('<html>', '').replace('</html>', '').split('<br>')
        lines.add([measurableTextOf(parts[0]), line1Metrics] as Object[])
        if (parts.length > 1) lines.add([measurableTextOf(parts[1]), crumbMetrics] as Object[])
    }
    lines.sort { a, b -> ((String) b[0]).length() - ((String) a[0]).length() }

    int max = 0
    lines.take(TOP_K).each { Object[] pair ->
        max = Math.max(max, ((FontMetrics) pair[1]).stringWidth((String) pair[0]))
    }
    // 8+8 from the matte border + rounding slack
    return max + 24
}

// strips the tags and undoes the escaping that resultItemHtml applied (the &amp; last)
String measurableTextOf(String htmlFragment) {
    return htmlFragment.replaceAll('<[^>]+>', '')
            .replace('&lt;', '<').replace('&gt;', '>').replace('&quot;', '"').replace('&amp;', '&')
}

void addHoverListenerRecursively(Component component) {
    component.addMouseListener(hoverListener)
    if (component instanceof Container) {
        ((Container) component).components.each { addHoverListenerRecursively(it) }
    }
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Panel ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
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

// height = what the content asks for (bar + field + result lines + status), capped
// by the viewport. empty list -> just the header; 500 results -> the full height, with scroll.
//
// The list's height comes from the OVERRIDDEN getPreferredScrollableViewportSize on resultsList
// (the real sum of the cells, handling 1 and 2 lines). The JList default returned
// visibleRowCount × the FIRST cell's height and underestimated — see the override in createResultsArea.
int fittedHeight(int panelWidth) {
    int preferred = searchPanel.getPreferredSize().height

    // The horizontal scroll bar (when a result's text overflows) is painted INSIDE
    // the scroll pane and eats ~17px off the bottom -> it would clip the last cell and trigger a
    // spurious vertical one, even with room to spare in the viewport. Reserve its height in
    // anticipation (getPreferredSize does not require it to already be visible). Capped by the
    // viewport: if there is no room to grow, then the vertical one is legitimate. Verified.
    if (horizontalScrollBarNeeded(panelWidth)) {
        preferred += resultsScrollPane.getHorizontalScrollBar().getPreferredSize().height
    }
    return Math.min(preferred, viewportHeight())
}

// Predicted from the width, not read from the layout: hsb.isVisible() only updates AFTER the layout
// settles (asynchronous), so reserving by it would give one frame of a spurious vertical bar. Here the
// computation is synchronous: does the widest content fit in the list's usable area?
boolean horizontalScrollBarNeeded(int panelWidth) {
    if (resultsScrollPane == null || results.size() == 0) return false

    int contentWidth = resultsList.getPreferredSize().width
    // if the lines already overflow the viewport height, the vertical bar will appear and steal width
    boolean verticalLikely = searchPanel.getPreferredSize().height > viewportHeight()
    int verticalWidth = verticalLikely ? resultsScrollPane.getVerticalScrollBar().getPreferredSize().width : 0
    int availableWidth = panelWidth - 2 * panelBorderThickness - verticalWidth

    return contentWidth > availableWidth
}

// the single point that sizes the panel: width and height always come from the same rule,
// and every trigger (hover, focus, search, resize) calls this.
void fitPanelBounds() {
    if (searchPanel == null) return

    boolean stayExpanded = mouseOverPanel || searchField.hasFocus()
    // wideMode wins: pins the width regardless of hover and focus
    int width = wideMode ? wideWidth() : (stayExpanded ? expandedWidth() : retractedWidth())
    animatePanelToWidth(width)
}

// width transition in short steps with ease-out, in place of the single-frame jump. The
// height follows each step (fittedHeight depends on the width). A new target mid-
// animation restarts from the current width -- no queue, no teleport.
void animatePanelToWidth(int targetWidth) {
    if (resizeAnimationTimer != null) {
        resizeAnimationTimer.stop()
        resizeAnimationTimer = null
    }

    int startWidth = searchPanel.getWidth()
    // width already at the target (e.g. results arrived): only the height may have changed -- no animation
    if (resizeAnimationSteps <= 1 || startWidth == targetWidth) {
        applyPanelBounds(targetWidth, true)
        return
    }

    int[] step = [0]
    resizeAnimationTimer = new Timer(resizeAnimationStepMs, { ActionEvent e ->
        step[0]++
        if (step[0] >= resizeAnimationSteps) {
            ((Timer) e.getSource()).stop()
            resizeAnimationTimer = null
            applyPanelBounds(targetWidth, true)
        } else {
            float t = step[0] / (float) resizeAnimationSteps
            float eased = 1f - (1f - t) * (1f - t)
            // intermediate frames do NOT redo the lines: the cost of refreshLines scales
            // with the number of results and made the animation slow on a giant map. It is safe
            // to skip: the lines' startX is read at PAINT time (it follows the panel on its own) and
            // the nodes do not move during the animation -- only the final frame recomputes.
            applyPanelBounds(startWidth + (int) ((targetWidth - startWidth) * eased), false)
        }
    } as ActionListener)
    resizeAnimationTimer.start()
}

void applyPanelBounds(int width, boolean refreshLinesAfter) {
    if (searchPanel == null) return   // late animation tick after the close

    int height = fittedHeight(width)
    if (searchPanel.getWidth() == width && searchPanel.getHeight() == height) return

    searchPanel.setBounds(0, 0, width, height)

    boundScrollPane.revalidate()
    boundScrollPane.repaint()

    if (!refreshLinesAfter) return
    // the map may shift -> the nodes change place.
    // recompute only after the layout settles.
    SwingUtilities.invokeLater { refreshLines() }
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Retract / expand ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Search ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void clearSearch() {
    searchField.setText("")
    // AFTER the setText, mandatorily: the setText fires the DocumentListener, which
    // restart()s the timer -- a stop before it would be undone on the next line. (If the timer
    // escaped, the phantom runSearch would be harmless: "" == lastSearchText and it returns.)
    liveSearchTimer.stop()

    hideNavigationTooltip()
    searchText = ""
    lastSearchText = ""
    currentTerms = new String[0]
    searchGeneration++   // invalidates any search still in flight
    results.clear()
    itemHtmlCache = [:]
    tooltipCache = [:]
    statusLabel.setText(" ")

    fitPanelBounds()
    refreshLines()
    searchField.requestFocusInWindow()
}

void runSearch() {
    if (searchPanel == null) return

    searchText = searchField.getText().trim()
    if (searchText == lastSearchText) return
    lastSearchText = searchText

    // the selection disappears with the old results; the popup would be pointing at nothing
    hideNavigationTooltip()

    final int generation = ++searchGeneration
    results.clear()

    if (searchText.isEmpty()) {
        currentTerms = new String[0]
        itemHtmlCache = [:]
        tooltipCache = [:]
        statusLabel.setText(" ")
        refreshLines()
        return
    }

    final NodeModel searchRoot = boundMapView.getMapSelection().getSelectionRoot()
    // terms folded the same way searchableTextOf folds the text: "coração" and "coracao" become
    // the same search, in both directions
    final String[] terms = searchText.toLowerCase().split("\\s+").collect { foldAccents(it) } as String[]
    currentTerms = terms
    statusLabel.setText("Searching...")
    final long searchStartedNanos = System.nanoTime()

    new SwingWorker<Void, Void>() {
        List<NodeModel> found = new ArrayList<NodeModel>()
        Map<NodeModel, String> htmlByNode = new HashMap<NodeModel, String>()
        Map<NodeModel, String> tooltipByNode = new HashMap<NodeModel, String>()
        int cellWidthPx
        long scanNanos
        long htmlNanos

        @Override
        protected Void doInBackground() throws Exception {
            // in a jump in, ancestors ABOVE the view root also count toward the search
            Set<String> satisfiedAboveRoot = terms.findAll { anyAncestorContains(searchRoot, it) } as Set
            long t0 = System.nanoTime()
            collectMatches(generation, searchRoot, terms, satisfiedAboveRoot, found)
            scanNanos = System.nanoTime() - t0

            // the renderer's expensive work moves off the EDT to here, once per result
            t0 = System.nanoTime()
            for (NodeModel node : found) {
                if (generation != searchGeneration) break   // superseded search: done() will discard everything
                htmlByNode.put(node, resultItemHtml(node, terms))
                tooltipByNode.put(node, ancestorsTooltip(node))
            }
            cellWidthPx = estimatedCellWidth(htmlByNode.values())
            htmlNanos = System.nanoTime() - t0
            return null
        }

        @Override
        protected void done() {
            if (generation != searchGeneration) return   // a newer search has already taken over
            itemHtmlCache = htmlByNode
            tooltipCache = tooltipByNode
            // BEFORE the addAll, so the layout it triggers is born cheap (see
            // fixedCellHeightPx: without the fixed sizes, the JList measures all 500 cells)
            resultsList.setFixedCellHeight(fixedCellHeightPx())
            resultsList.setFixedCellWidth(Math.max(24, cellWidthPx))
            // addAll = a single intervalAdded; addElement in a loop fired one per result
            long t0 = System.nanoTime()
            results.addAll(found)
            long addNanos = System.nanoTime() - t0

            t0 = System.nanoTime()
            fitPanelBounds()
            long fitNanos = System.nanoTime() - t0

            t0 = System.nanoTime()
            refreshLines()
            long linesNanos = System.nanoTime() - t0

            // per-phase stopwatch, so "the search is slow" points to the right culprit:
            // scan = collectMatches | html = chain/tooltip | add/fit/lines = the EDT phases
            // total = from the search trigger to the panel ready (includes the EDT wait)
            String timing = " [scan " + scanNanos.intdiv(1_000_000) + " · html " + htmlNanos.intdiv(1_000_000) +
                    " · add " + addNanos.intdiv(1_000_000) + " · fit " + fitNanos.intdiv(1_000_000) +
                    " · lines " + linesNanos.intdiv(1_000_000) +
                    " · total " + (System.nanoTime() - searchStartedNanos).intdiv(1_000_000) + " ms]"
            statusLabel.setText((found.size() >= maxNumberOfResults
                    ? "Max number of results (" + maxNumberOfResults + ") reached."
                    : found.size() + " results. Finished.") + timing)
        }
    }.execute()
}

// The recursion carries down the terms already satisfied by the ancestors: each text
// goes through toLowerCase ONCE per search and no one walks up the tree per node — O(N×terms),
// against the O(N×depth×terms) of the version that called anyAncestorContains per node.
// Semantics identical to matchesTransversally (the oracle, below): an ancestor contains the
// term ⇔ the term entered the set at some level above.
void collectMatches(int generation, NodeModel node, String[] terms, Set<String> satisfiedAbove, List<NodeModel> found) {
    if (found.size() >= maxNumberOfResults) return
    if (generation != searchGeneration) return   // superseded search: aborts the scan

    String nodeText = searchableTextOf(node)
    List<String> foundDirectly = terms.findAll { nodeText.contains(it) }

    if (!foundDirectly.isEmpty()
            && terms.every { foundDirectly.contains(it) || satisfiedAbove.contains(it) }) {
        found.add(node)
    }

    Set<String> satisfiedHere = foundDirectly.isEmpty() || satisfiedAbove.containsAll(foundDirectly)
            ? satisfiedAbove
            : (satisfiedAbove + foundDirectly)

    for (NodeModel child : node.getChildren()) {
        if (found.size() >= maxNumberOfResults) return
        collectMatches(generation, child, terms, satisfiedHere, found)
    }
}

// the SAME text the chain assembly consults: if they diverge, a node can match in the
// search and appear without the ancestors that made it match.
//
// htmlToPlain because a rich-text node stores MARKUP in getText() ("<html>...<b>..."):
// searching the raw text made "font"/"b" match formatting -- and the highlight (which always
// saw the plain text) did not find the term that had made the node match. Now search,
// chain and highlight read the SAME plain text. Cheap on the hot path: with strictHTMLOnly
// htmlToPlain returns the text intact if it does not start with <html>
// (HtmlUtils.java:144-146) -- only a rich node pays the regexes -- and there is a thread-local cache
// of the last input (:118-120), which serves the repetitions in the ancestor chains.
//
// foldAccents on top: "coracao" finds "coração" (the terms are folded the same way in runSearch).
String searchableTextOf(NodeModel node) {
    return foldAccents(HtmlUtils.htmlToPlain(node.getText() ?: "").toLowerCase())
}

// ── accent folding ─────────────────────────────────────────────────────────
// The folding is PER CHARACTER, and that is a CONTRACT: the output always has the SAME length as the
// input, so the ranges mergedMatchRanges finds in the folded text paint the ORIGINAL
// text without a position map. That is why you must NOT swap it for Normalizer.normalize over the
// whole string: NFD changes the length (ç becomes c + combining cedilla) and misaligns the highlight.
//
// Accepted limitation: source text that ALREADY arrives decomposed (combining mark as a
// separate character -- rare in a .mm typed on Windows, which produces NFC) is not folded: the mark stays in
// place, preserving the length.
String foldAccents(String text) {
    StringBuilder out = null   // only allocates if there is something to fold; pure ASCII passes intact
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

// cache because Normalizer.normalize per call costs ~µs and the same ~30 accented characters of
// Portuguese repeat by the thousands in a scan. ConcurrentHashMap: overlapping searches (delay 0)
// run in concurrent workers, and the renderer on the EDT also lands here via the fallback.
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

// No longer called by the search (collectMatches is the equivalent optimized version), but
// it stays as a per-node ORACLE: it is against it that the cross-cutting search's assertions test,
// and any change of semantics has to keep the two in agreement.
boolean matchesTransversally(NodeModel node, String[] terms) {
    String nodeText = searchableTextOf(node)

    List<String> foundDirectly = terms.findAll { nodeText.contains(it) }
    if (foundDirectly.isEmpty()) return false

    return terms.every { term -> foundDirectly.contains(term) || anyAncestorContains(node, term) }
}

boolean anyAncestorContains(NodeModel node, String term) {
    NodeModel ancestor = node.getParentNode()
    while (ancestor != null) {
        if (searchableTextOf(ancestor).contains(term)) return true
        ancestor = ancestor.getParentNode()
    }
    return false
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Search ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Connection lines ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void createLinesOverlay() {
    solidLineStroke = new BasicStroke(lineThickness)
    dashedLineStroke = new BasicStroke(lineThickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
            1f, [1f, 4f] as float[], 0f)
    emphasizedSolidStroke = new BasicStroke(emphasizedLineThickness)
    emphasizedDashedStroke = new BasicStroke(emphasizedLineThickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
            1f, [1f, 4f] as float[], 0f)

    linesOverlay = new JPanel() {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g)

            Graphics2D g2d = (Graphics2D) g.create()
            try {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                // read on every paint: the panel changes width on hover
                int startX = searchPanel.getX() + searchPanel.getWidth()

                int activeIndex = activeLineIndex()
                boolean hasActive = activeIndex >= 0

                // the non-active ones first (faded if there is an active one), the active one on top
                lineData.each { data ->
                    if (hasActive && (data.index as int) == activeIndex) return
                    paintConnectionLine(g2d, data, startX, hasActive, false)
                }
                if (hasActive) {
                    Map activeData = lineData.find { (it.index as int) == activeIndex }
                    if (activeData != null) paintConnectionLine(g2d, activeData, startX, false, true)
                }
            } finally {
                g2d.dispose()
            }
        }
    }
    linesOverlay.setName(OVERLAY_NAME)
    linesOverlay.setOpaque(false)
    linesOverlay.setBounds(0, 0, boundScrollPane.getWidth(), boundScrollPane.getHeight())

    boundScrollPane.add(linesOverlay)
    boundScrollPane.setComponentZOrder(linesOverlay, 0)
    // the panel goes back to the front: the overlay was added after it
    boundScrollPane.setComponentZOrder(searchPanel, 0)
}

// dimmed = fades the color (low alpha, when there is an active line and this is not it).
// emphasized = thickens the stroke (the active line). Never both at the same time.
void paintConnectionLine(Graphics2D g2d, Map data, int startX, boolean dimmed, boolean emphasized) {
    Point end = data.end as Point
    boolean dashed = data.dashed as boolean
    g2d.setStroke(dashed
            ? (emphasized ? emphasizedDashedStroke : dashedLineStroke)
            : (emphasized ? emphasizedSolidStroke : solidLineStroke))

    Color base = data.color as Color
    g2d.setColor(dimmed ? new Color(base.getRed(), base.getGreen(), base.getBlue(), dimmedLineAlpha) : base)

    g2d.drawLine(startX, data.startY as int, end.x as int, end.y as int)
}

void refreshLines() {
    if (searchPanel == null || linesOverlay == null) return

    lineData.clear()

    // ALL cells, visible or not — the user's decision: a line for each result
    NodeModel viewRoot = boundMapView.getMapSelection().getSelectionRoot()
    for (int index = 0; index < results.size(); index++) {
        addLineData(index, viewRoot)
    }

    linesOverlay.setBounds(0, 0, boundScrollPane.getWidth(), boundScrollPane.getHeight())
    linesOverlay.repaint()
}

void addLineData(int index, NodeModel viewRoot) {
    Rectangle cellBounds = resultsList.getCellBounds(index, index)
    if (cellBounds == null) return

    NodeModel node = results.getElementAt(index)
    if (!node.getPathToRoot().any { it == viewRoot }) return

    boolean insideFoldedAncestor = false
    NodeView nodeView = boundMapView.getNodeView(node)
    if (nodeView == null) {
        // no view = hidden inside a folded ancestor; the line goes up to it
        nodeView = nearestAncestorWithView(node)
        if (nodeView == null) return
        insideFoldedAncestor = true
    }

    Point start = new Point(cellBounds.getLocation())
    UITools.convertPointToAncestor(resultsList, start, boundScrollPane)

    Point end = boundMapView.getNodeContentLocation(nodeView)
    UITools.convertPointToAncestor(boundMapView, end, boundScrollPane)

    lineData.add([
            index  : index,   // the result's index, so the emphasis matches the active line
            startY : start.y + (int) (cellBounds.height / 2),
            // getContent() is the same component that getNodeContentLocation measured
            end    : new Point(end.x as int, (end.y + (int) (nodeView.getContent().getHeight() / 2)) as int),
            color  : colorForIndex(index),
            dashed : insideFoldedAncestor
    ] as Map<String, Object>)
}

NodeView nearestAncestorWithView(NodeModel node) {
    NodeModel ancestor = node.getParentNode()
    while (ancestor != null) {
        NodeView ancestorView = boundMapView.getNodeView(ancestor)
        if (ancestorView != null) return ancestorView
        ancestor = ancestor.getParentNode()
    }
    return null
}

Color colorForIndex(int index) {
    List<Color> palette = isColorDark(boundMapView.getBackground()) ? COLORS_FOR_DARK_BG : COLORS_FOR_LIGHT_BG
    return palette[index % palette.size()]
}

boolean isColorDark(Color color) {
    if (color == null) return false
    double luminance = 0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue()
    return luminance < 128.0
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Connection lines ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/
