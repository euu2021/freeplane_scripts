// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2962
// Version: 1.0

/*
 * Undo Stack Viewer
 * -----------------
 * Shows what the current map's undo stack contains: most recent action first,
 * what each one actually did (which node, old value -> new value), and where
 * the undo/redo cursor sits right now.
 *
 * Use case: you clicked something by accident, the map feels different, and you
 * want to know what changed -- above all whether a node was deleted -- without
 * pressing Ctrl+Z blindly to find out.
 *
 * The window follows the map: it refreshes itself on every action, undo and
 * redo, so it can be left open as a monitor. Walking the rows selects the node
 * each action touched (for a deleted node, its former parent). Nothing is
 * modified by looking.
 *
 * The "Step" column is how many Ctrl+Z away the action is -- and it stays true
 * when rows are hidden by the noise filter. R1, R2… are ahead of the cursor
 * (redo), shown greyed and italic.
 *
 * Ctrl+Z and Ctrl+Y work while this window has focus, and "Undo N actions"
 * takes the map back to the selected row in one go.
 *
 * Worth knowing:
 *   - the stack lives in memory, per map, capped at 100 actions, and starts
 *     empty every time the map is opened;
 *   - "Time" is only filled for actions that touch node content, because that
 *     is where Freeplane happens to record a timestamp; blank means unknown,
 *     not "long ago";
 *   - details are read from Freeplane internals. On a version that renames
 *     those fields the viewer falls back to the short action name rather than
 *     breaking.
 */

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.lang.reflect.Field
import java.text.SimpleDateFormat
import java.util.List

import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

import org.freeplane.core.ui.components.UITools
import org.freeplane.features.map.NodeModel
import org.freeplane.features.mode.Controller

MAX_TEXT = 60
ANCESTRY_LEVELS = 3
DIFF_CONTEXT = 18
UNDO_HANDLER_CLASS = 'org.freeplane.core.undo.IUndoHandler'
TIME_FORMAT = new SimpleDateFormat('HH:mm:ss')
MARKER = 'undoStackViewer'

// Actor descriptions that carry nothing for a human reader.
NOISE = ['Restore selection', 'delayedNodeRefresh', 'refreshMapLaterUndoable',
         'setLastModifiedAt', 'setCreatedAt', 'BlinkingNodeHook.timer'] as Set

// Kinds the noise filter hides: things that never damage a map.
HIDDEN_KINDS = ['folding', 'view', 'side'] as Set

// Highest wins when one user action bundles several actors.
PRIORITY = [delete: 100, add: 95, move: 90, text: 85, attribute: 80, tag: 75, icon: 70,
            link: 65, connector: 60, style: 40, side: 30, view: 25, folding: 20, generic: 10]

/*
 * Freeplane action description -> [kind, human name]. Built from the actual
 * getDescription() strings in the source, so the window says "Added icon"
 * rather than "Add icon" spelled out of a camelCase guess. Anything not listed
 * still gets a readable name from humanize().
 */
KNOWN = [
    'delete'                        : ['delete', 'Deleted node'],
    'addNewNode'                    : ['add', 'Added node'],
    'copy'                          : ['add', 'Pasted'],
    'moveNode'                      : ['move', 'Moved node'],
    'moveNodePosition'              : ['move', 'Moved free node'],
    'convertClonesToIndependentNodes': ['move', 'Detached clones'],
    'change side'                   : ['side', 'Changed side'],
    'setFoldingState'               : ['folding', 'Folding'],
    'setNodeText'                   : ['text', 'Changed text'],
    'setShortener'                  : ['text', 'Minimized node'],
    'setDetailsHidden'              : ['text', 'Details visibility'],
    'setNodeFormat'                 : ['text', 'Changed format'],
    'setNodeNumbering'              : ['text', 'Numbering'],
    'setAlias'                      : ['text', 'Changed alias'],
    'addIcon'                       : ['icon', 'Added icon'],
    'removeIcon'                    : ['icon', 'Removed icon'],
    'changeIconSize'                : ['icon', 'Icon size'],
    'setTags'                       : ['tag', 'Changed tags'],
    'setTagCategories'              : ['tag', 'Edited tag categories'],
    'set tag color'                 : ['tag', 'Tag colour'],
    'setLink'                       : ['link', 'Changed link'],
    'addLink'                       : ['link', 'Added link'],
    'deleteMapLinks'                : ['link', 'Removed links'],
    'addArrowLink'                  : ['connector', 'Added connector'],
    'removeArrowLink'               : ['connector', 'Removed connector'],
    'setArrowLinkEndPoints'         : ['connector', 'Moved connector'],
    'changeArrowsOfArrowLink'       : ['connector', 'Connector arrows'],
    'setSourceLabel'                : ['connector', 'Connector label'],
    'setMiddleLabel'                : ['connector', 'Connector label'],
    'setTargetLabel'                : ['connector', 'Connector label'],
    'SetAttributeValue'             : ['attribute', 'Attribute value'],
    'ReplaceAttributeValueActor'    : ['attribute', 'Attribute value'],
    'InsertAttributeActor'          : ['attribute', 'Added attribute'],
    'RemoveAttributeActor'          : ['attribute', 'Removed attribute'],
    'setAttributeName'              : ['attribute', 'Attribute name'],
    'SetAttributeVisibleActor'      : ['attribute', 'Attribute visibility'],
    'setBackgroundColor'            : ['style', 'Background colour'],
    'setColor'                      : ['style', 'Colour'],
    'setStyle'                      : ['style', 'Changed style'],
    'setShape'                      : ['style', 'Node shape'],
    'setCloud'                      : ['style', 'Cloud'],
    'encrypt'                       : ['style', 'Encrypted'],
    'removeEncryption'              : ['style', 'Removed encryption'],
    'toggleCryptState'              : ['style', 'Locked/unlocked'],
    'setFilterCondition'            : ['view', 'Filter'],
    'setRootNodeId'                 : ['view', 'View root'],
    'setSelectedNodeIds'            : ['view', 'Selection'],
    'addSelectedNodeIds'            : ['view', 'Selection'],
    'removeSelectedNodeIds'         : ['view', 'Selection'],
    'setCurrentFoldedNodeIDs'       : ['view', 'Folded set'],
    'unsetFoldsNodes'               : ['view', 'Folded set'],
    'setChangesZoom'                : ['view', 'Zoom'],
    'setModelSize'                  : ['view', 'Map size'],
]

// Description prefixes that place an unlisted action into a kind.
KIND_PREFIXES = [
    'setBorder': 'style', 'setFont': 'style', 'setBold': 'style', 'setItalic': 'style',
    'setUnderlined': 'style', 'setStrikedThrough': 'style', 'setWidth': 'style',
    'setMinNodeWidth': 'style', 'setMaxNodeWidth': 'style', 'setHorizontalTextAlignment': 'style',
    'changeVGap': 'style', 'changeBaseHHap': 'style', 'changeChildNodesLayout': 'style',
    'setConnector': 'connector', 'setDash': 'connector',
    'Registry': 'attribute', 'Unregistry': 'attribute', 'SetAttribute': 'attribute',
    'setPlace': 'view', 'setShows': 'view',
]

Object fieldValue(Object owner, String name) {
    Class cl = owner?.getClass()
    while (cl != null) {
        try {
            Field f = cl.getDeclaredField(name)
            f.setAccessible(true)
            return f.get(owner)
        }
        catch (NoSuchFieldException e) { cl = cl.getSuperclass() }
        catch (Exception e) { return null }
    }
    return null
}

/** Captured locals of an anonymous IActor, with the val$ prefix stripped. */
Map capturedFields(Object actor) {
    Map values = new LinkedHashMap()
    try {
        actor.getClass().getDeclaredFields().each { Field f ->
            if (f.name.startsWith('this$')) return
            try {
                f.setAccessible(true)
                values.put(f.name.startsWith('val$') ? f.name.substring(4) : f.name, f.get(actor))
            }
            catch (Exception ignored) {}
        }
    }
    catch (Exception ignored) {}
    return values
}

String flatten(Object value) {
    String text = (value instanceof NodeModel) ? ((NodeModel) value).getText() : String.valueOf(value)
    if (text == null) return ''
    return text.replaceAll(/(?s)<[^>]+>/, ' ').replace('&nbsp;', ' ').replaceAll(/\s+/, ' ').trim()
}

String plain(Object value) {
    String text = flatten(value)
    return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + '…' : text
}

/** 'grandparent › parent › node' -- enough context to tell two 'ideas' apart. */
String ancestry(Object value, int levels) {
    if (!(value instanceof NodeModel)) return plain(value)
    List names = []
    NodeModel current = (NodeModel) value
    while (current != null && names.size() < levels) {
        String text = flatten(current)
        names.add(0, text.length() > 28 ? text.substring(0, 28) + '…' : text)
        current = current.getParentNode()
    }
    return names.join(' › ')
}

String valueLabel(Object v) {
    if (v == null) return '(none)'
    if (v instanceof Color) {
        Color c = (Color) v
        return String.format('#%02x%02x%02x', c.getRed(), c.getGreen(), c.getBlue())
    }
    if (v instanceof NodeModel) return "'" + plain(v) + "'"
    String s = plain(v)
    return s.isEmpty() ? '(empty)' : s
}

String humanize(String description) {
    if (description == null || description.trim().isEmpty()) return 'Change'
    String s = description.trim()
    if (s.startsWith('set')) s = s.substring(3)
    s = s.replaceAll(/([a-z0-9])([A-Z])/, '$1 $2').toLowerCase()
    return s.isEmpty() ? 'Change' : s.substring(0, 1).toUpperCase() + s.substring(1)
}

/*
 * 'quero que ela seja aberta' -> 'quero que ela sej[a → am] aberta': show the
 * bit that actually changed, with a little context. On a long node the plain
 * before -> after pair truncates away exactly the interesting part.
 */
String textDiff(Object oldValue, Object newValue) {
    String a = flatten(oldValue)
    String b = flatten(newValue)
    if (a.isEmpty()) return "(empty) → '${b.length() > MAX_TEXT ? b.substring(0, MAX_TEXT) + '…' : b}'"
    if (b.isEmpty()) return "'${a.length() > MAX_TEXT ? a.substring(0, MAX_TEXT) + '…' : a}' → (empty)"

    int prefix = 0
    int max = Math.min(a.length(), b.length())
    while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) prefix++
    int suffix = 0
    while (suffix < max - prefix && a.charAt(a.length() - 1 - suffix) == b.charAt(b.length() - 1 - suffix)) suffix++

    String oldMiddle = a.substring(prefix, a.length() - suffix)
    String newMiddle = b.substring(prefix, b.length() - suffix)
    if (oldMiddle.isEmpty() && newMiddle.isEmpty()) return '(no visible change)'

    String before = a.substring(Math.max(0, prefix - DIFF_CONTEXT), prefix)
    String after = a.substring(a.length() - suffix, Math.min(a.length(), a.length() - suffix + DIFF_CONTEXT))
    String leading = (prefix > DIFF_CONTEXT) ? '…' : ''
    String trailing = (suffix > DIFF_CONTEXT) ? '…' : ''
    if (oldMiddle.length() > MAX_TEXT) oldMiddle = oldMiddle.substring(0, MAX_TEXT) + '…'
    if (newMiddle.length() > MAX_TEXT) newMiddle = newMiddle.substring(0, MAX_TEXT) + '…'
    return "${leading}${before}[${oldMiddle} → ${newMiddle}]${after}${trailing}"
}

/** One actor -> [kind, action, detail, node]; node is what to select in the map. */
Map describeActor(Object actor) {
    String desc = null
    try { desc = actor.getDescription() } catch (Exception ignored) {}
    Map f = capturedFields(actor)

    if (desc == 'delete') {
        return [kind: 'delete', action: 'Deleted node',
                detail: "'${plain(f.node)}' — was child #${f.index} of ${ancestry(f.parentNode, ANCESTRY_LEVELS)}".toString(),
                node: f.parentNode]
    }
    if (desc == 'addNewNode') {
        return [kind: 'add', action: 'Added node',
                detail: "'${plain(f.newNode)}' under ${ancestry(f.parent, ANCESTRY_LEVELS)}".toString(),
                node: f.newNode]
    }
    if (desc == 'setNodeText') {
        return [kind: 'text', action: 'Changed text',
                detail: "${ancestry(f.node, 2)}: ${textDiff(f.oldText, f.newObject)}".toString(),
                node: f.node]
    }
    if (desc == 'moveNode') {
        return [kind: 'move', action: 'Moved node',
                detail: "'${plain(f.child)}': ${ancestry(f.oldParent, 2)} #${f.oldIndex} → ${ancestry(f.newParent, 2)} #${f.newIndex}".toString(),
                node: f.child]
    }
    if (desc == 'setFoldingState') {
        return [kind: 'folding', action: f.folded ? 'Folded' : 'Unfolded',
                detail: ancestry(f.node, 2), node: f.node]
    }

    NodeModel target = (NodeModel) f.values().find { it instanceof NodeModel }
    String oldKey = f.keySet().find {
        String k = it.toLowerCase()
        (k.startsWith('old') || k.startsWith('was')) && k.length() > 3
    }
    String detail
    if (oldKey != null) {
        String base = oldKey.substring(3)
        String newKey = f.keySet().find { it != oldKey && (it.equalsIgnoreCase(base) || it.equalsIgnoreCase('new' + base)) }
        detail = "${valueLabel(f[oldKey])} → ${valueLabel(newKey == null ? null : f[newKey])}".toString()
        if (target != null) detail = "${ancestry(target, 2)}: ${detail}".toString()
    }
    else {
        detail = (target == null) ? '' : ancestry(target, ANCESTRY_LEVELS)
    }

    List known = KNOWN.get(desc)
    if (known != null) return [kind: known[0], action: known[1], detail: detail, node: target]
    String kind = 'generic'
    KIND_PREFIXES.each { String prefix, String k -> if (desc != null && desc.startsWith(prefix)) kind = k }
    return [kind: kind, action: humanize(desc), detail: detail, node: target]
}

/** Freeplane stamps node content changes with a Date; use it when present. */
Date timestampOf(Object actor) {
    Object value = capturedFields(actor).get('now')
    return (value instanceof Date) ? (Date) value : null
}

/** -> [rows (newest first), cursor] */
List buildRows(Object undoHandler) {
    Object actorList = fieldValue(undoHandler, 'actorList')
    Object iterator = fieldValue(undoHandler, 'actorIterator')

    /*
     * Running from the Scripts menu, Freeplane wraps the script in an undo
     * transaction (ExecuteScriptAction:102), and startTransaction swaps
     * actorList for a fresh empty one so that everything the script does
     * collapses into a single undo step. While that is open the map's real
     * stack is the bottom entry of transactionList -- read it there, otherwise
     * this window reports an empty stack whenever it is opened from the menu.
     */
    int transactionLevel = 0
    try { transactionLevel = undoHandler.getTransactionLevel() } catch (Exception ignored) {}
    if (transactionLevel > 0) {
        Object stacked = fieldValue(undoHandler, 'transactionList')
        Object stackedIterators = fieldValue(undoHandler, 'transactionIteratorList')
        if (stacked != null && !stacked.isEmpty()) {
            actorList = stacked.getFirst()
            iterator = (stackedIterators == null || stackedIterators.isEmpty()) ? null : stackedIterators.getFirst()
        }
    }

    if (actorList == null) return [[], 0]
    int cursor
    try { cursor = (iterator == null) ? actorList.size() : iterator.nextIndex() }
    catch (Exception ignored) { cursor = actorList.size() }

    List rows = []
    actorList.eachWithIndex { compound, int i ->
        List inner = (List) fieldValue(compound, 'actors')
        List parts = []
        Date stamp = null
        (inner == null ? [] : inner).each { a ->
            Date t = timestampOf(a)
            if (t != null && (stamp == null || t.after(stamp))) stamp = t
            String d = null
            try { d = a.getDescription() } catch (Exception ignored) {}
            if (d == null || d.trim().isEmpty() || NOISE.contains(d)) return
            parts << describeActor(a)
        }
        if (parts.isEmpty()) return
        rows << [position: i, isRedo: i >= cursor, time: stamp,
                 main: parts.max { PRIORITY.get(it.kind, 0) }, parts: parts]
    }
    return [rows.reverse(), cursor]
}

String stepLabel(Map row, int cursor) {
    return row.isRedo ? "R${row.position - cursor + 1}".toString() : String.valueOf(cursor - row.position)
}

String detailFor(Map row) {
    int extra = row.parts.size() - 1
    return extra > 0 ? "${row.main.detail}  (+${extra} more)".toString() : row.main.detail
}

String longDetail(Map row, int cursor) {
    StringBuilder sb = new StringBuilder()
    sb << (row.isRedo ? "Ahead of the cursor: ${row.position - cursor + 1} redo step(s) away"
                      : "${cursor - row.position} Ctrl+Z away")
    if (row.time != null) sb << '   —   ' << TIME_FORMAT.format(row.time)
    sb << '\n'
    row.parts.each { p -> sb << "\n• ${p.action}: ${p.detail}" }
    return sb.toString()
}

// --- gather -----------------------------------------------------------------

def mapModel = node.mindMap.delegate
def undoHandler = mapModel.getExtension(Class.forName(UNDO_HANDLER_CLASS))
if (undoHandler == null) {
    UITools.informationMessage('This map has no undo handler.')
    return
}

// One window per run: an older one would keep its own change listener alive.
Window.getWindows().each { w ->
    if (w instanceof JDialog && ((JDialog) w).getRootPane()?.getClientProperty(MARKER) != null) {
        w.setVisible(false)
        w.dispose()
    }
}

// Held in one object so the inner classes below always see current data.
def state = [rows: [], visible: [], cursor: 0, syncing: false, hideNoise: true]

def load = {
    def built = buildRows(undoHandler)
    state.rows = (List) built[0]
    state.cursor = (int) built[1]
    state.visible = state.hideNoise ? state.rows.findAll { !HIDDEN_KINDS.contains(it.main.kind) } : state.rows
}
load()

// --- window -----------------------------------------------------------------

boolean light = UITools.isLightLookAndFeelInstalled()
Color deleteColor = light ? new Color(0xB0, 0x00, 0x20) : new Color(0xFF, 0x8A, 0x80)
Color addColor = light ? new Color(0x1B, 0x5E, 0x20) : new Color(0x9C, 0xCC, 0x65)
Color fadedColor = light ? new Color(0x80, 0x80, 0x80) : new Color(0x99, 0x99, 0x99)

def toTableData = {
    return state.visible.collect { Map row ->
        [stepLabel(row, state.cursor),
         row.time == null ? '' : TIME_FORMAT.format(row.time),
         row.main.action,
         detailFor(row)] as Object[]
    } as Object[][]
}

def columns = ['Step', 'Time', 'Action', 'What changed'] as Object[]
def model = new DefaultTableModel(toTableData(), columns) {
    @Override boolean isCellEditable(int r, int c) { return false }
}

def table = new JTable(model) {
    @Override String getToolTipText(MouseEvent e) {
        int r = rowAtPoint(e.getPoint())
        if (r < 0 || r >= state.visible.size()) return null
        Map row = state.visible[convertRowIndexToModel(r)]
        StringBuilder sb = new StringBuilder('<html>')
        row.parts.each { p -> sb << "<b>${p.action}</b>: ${String.valueOf(p.detail).replace('<', '&lt;')}<br>" }
        return sb.append('</html>').toString()
    }
}
table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
table.setRowHeight(Math.max(table.getRowHeight(), 22))
table.setShowVerticalLines(false)

def renderer = new DefaultTableCellRenderer() {
    @Override
    Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                                            boolean hasFocus, int r, int col) {
        Component comp = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, r, col)
        if (r < 0 || r >= state.visible.size()) return comp
        Map row = state.visible[t.convertRowIndexToModel(r)]
        if (!isSelected) {
            if (row.isRedo) comp.setForeground(fadedColor)
            else if (row.main.kind == 'delete') comp.setForeground(deleteColor)
            else if (row.main.kind == 'add') comp.setForeground(addColor)
            else comp.setForeground(t.getForeground())
        }
        comp.setFont(comp.getFont().deriveFont(row.isRedo ? Font.ITALIC : Font.PLAIN))
        return comp
    }
}

// The first three columns are sized to their widest cell, so nothing is cut
// off whatever the font or look and feel; the last one takes what is left.
def applyColumnLayout = {
    (0..3).each { table.getColumnModel().getColumn(it).setCellRenderer(renderer) }
    (0..2).each { int col ->
        def column = table.getColumnModel().getColumn(col)
        int width = table.getTableHeader().getDefaultRenderer()
                .getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, col)
                .getPreferredSize().width
        (0..<table.getRowCount()).each { int row ->
            width = Math.max(width, table.prepareRenderer(table.getCellRenderer(row, col), row, col)
                    .getPreferredSize().width)
        }
        width += 12
        column.setMinWidth(width)
        column.setPreferredWidth(width)
        column.setMaxWidth(width)
    }
    def last = table.getColumnModel().getColumn(3)
    last.setMinWidth(200)
    last.setPreferredWidth(600)
    last.setMaxWidth(Integer.MAX_VALUE)
}
applyColumnLayout()
table.getTableHeader().setToolTipText('Step: how many Ctrl+Z away the action is. R1, R2… are ahead of the cursor (redo).')

def dialog = new JDialog(UITools.getCurrentFrame(), 'Undo stack — ' + node.mindMap.name, false)
dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
dialog.getRootPane().putClientProperty(MARKER, Boolean.TRUE)

def detailArea = new JTextArea(4, 20)
detailArea.setEditable(false)
detailArea.setLineWrap(true)
detailArea.setWrapStyleWord(true)
detailArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8))
// A JTextArea defaults to a monospaced font, which reads as "code" next to the
// table; borrow the label font so the panel looks like part of the window.
def labelFont = javax.swing.UIManager.getFont('Label.font')
if (labelFont != null) detailArea.setFont(labelFont)
detailArea.setText('Select a row to see everything that action did.')

def status = new JLabel()
status.setForeground(fadedColor)
def emptyLabel = new JLabel('<html><center>Nothing in the undo stack of this map yet.<br><br>'
        + 'The stack is <b>per map</b> and starts empty every time a map is opened,<br>'
        + 'so this is the stack of the map holding the selected node — nothing else.</center></html>')
emptyLabel.setHorizontalAlignment(JLabel.CENTER)

def undoToHere = new JButton('Undo to here')
undoToHere.setEnabled(false)
// Freeze the width at the longest label it will ever hold: the text changes
// with every selection, and a button that resizes on each click both jitters
// and squeezes the status text out of the row.
undoToHere.setText('Redo 100 action(s)')
def undoButtonSize = undoToHere.getPreferredSize()
undoToHere.setPreferredSize(undoButtonSize)
undoToHere.setMinimumSize(undoButtonSize)
undoToHere.setMaximumSize(undoButtonSize)
undoToHere.setText('Undo to here')

def selectedRow = {
    int r = table.getSelectedRow()
    return (r < 0 || r >= state.visible.size()) ? null : state.visible[table.convertRowIndexToModel(r)]
}

def describeState = {
    int hidden = state.rows.size() - state.visible.size()
    int redoable = state.rows.count { it.isRedo }
    int undoable = state.rows.size() - redoable
    String hiddenText = hidden > 0 ? " · ${hidden} hidden" : ''
    status.setText("${state.rows.size()} actions · ${undoable} undo · ${redoable} redo${hiddenText}".toString())
    status.setToolTipText("${state.rows.size()} action(s) in this map's stack: ${undoable} can be undone, "
            + "${redoable} can be redone, ${hidden} hidden by the filter. The stack holds at most 100 actions "
            + 'and starts empty every time the map is opened.')
}

def describeButton = {
    Map row = selectedRow()
    if (row == null) {
        undoToHere.setEnabled(false)
        undoToHere.setText('Undo to here')
        return
    }
    int steps = row.isRedo ? (row.position - state.cursor + 1) : (state.cursor - row.position)
    undoToHere.setEnabled(steps > 0)
    undoToHere.setText(row.isRedo ? "Redo ${steps} action(s)".toString() : "Undo ${steps} action(s)".toString())
}

def centre = new JPanel(new CardLayout())
centre.add(new JScrollPane(table), 'table')
centre.add(emptyLabel, 'empty')
def showCentre = {
    ((CardLayout) centre.getLayout()).show(centre, state.visible.isEmpty() ? 'empty' : 'table')
}

/*
 * Rebuild after any change. The table selection is dropped rather than
 * restored: this runs while the user is working in the map, and re-selecting a
 * row would drag the map selection (and the focus) around under their hands.
 */
def reload = {
    state.syncing = true
    try {
        load()
        model.setDataVector(toTableData(), columns)
        applyColumnLayout()
        table.clearSelection()
    }
    finally { state.syncing = false }
    describeState()
    describeButton()
    showCentre()
    detailArea.setText('Select a row to see everything that action did.')
}
describeState()
showCentre()

// Walking the rows shows the node in the map, then hands focus back to the
// table so the arrow keys keep walking the stack.
table.getSelectionModel().addListSelectionListener { e ->
    if (e.getValueIsAdjusting() || state.syncing) return
    describeButton()
    Map row = selectedRow()
    if (row == null) return
    detailArea.setText(longDetail(row, state.cursor))
    detailArea.setCaretPosition(0)
    NodeModel target = (NodeModel) row.main.node
    if (target == null) return
    if (!target.is(mapModel.getNodeForID(target.getID()))) return
    try {
        Controller.getCurrentModeController().getMapController().displayNode(target)
        Controller.getCurrentController().getSelection().selectAsTheOnlyOneSelected(target)
    }
    catch (Exception ignored) {}
    SwingUtilities.invokeLater { table.requestFocusInWindow() }
}

// Follow the map: every action, undo and redo fires this.
def changeListener = { ChangeEvent e -> SwingUtilities.invokeLater(reload) } as ChangeListener
undoHandler.addChangeListener(changeListener)
dialog.addWindowListener(new WindowAdapter() {
    @Override
    void windowClosed(WindowEvent e) {
        try { undoHandler.removeChangeListener(changeListener) } catch (Exception ignored) {}
    }
})

undoToHere.addActionListener({ ActionEvent e ->
    Map row = selectedRow()
    if (row == null) return
    if (row.isRedo) {
        (row.position - state.cursor + 1).times { if (undoHandler.canRedo()) undoHandler.redo() }
    }
    else {
        (state.cursor - row.position).times { if (undoHandler.canUndo()) undoHandler.undo() }
    }
} as ActionListener)

def noiseFilter = new JCheckBox('Hide folding/view', true)
noiseFilter.setToolTipText('Hides folding, side and view-only actions — the ones that never damage a map.')
noiseFilter.addActionListener({ ActionEvent e ->
    state.hideNoise = noiseFilter.isSelected()
    reload()
} as ActionListener)

def refreshButton = new JButton('Refresh')
refreshButton.addActionListener({ ActionEvent e -> reload() } as ActionListener)

def closeButton = new JButton('Close')
closeButton.addActionListener({ ActionEvent e -> dialog.dispose() } as ActionListener)

// BorderLayout, not BoxLayout: the status text grows with the map and a
// BoxLayout would honour its preferred width and push the buttons off the
// window. In the CENTER slot it is the label that gets clipped instead.
def buttonRow = new JPanel()
buttonRow.setLayout(new BoxLayout(buttonRow, BoxLayout.X_AXIS))
buttonRow.add(noiseFilter)
buttonRow.add(Box.createHorizontalStrut(10))
buttonRow.add(undoToHere)
buttonRow.add(Box.createHorizontalStrut(6))
buttonRow.add(refreshButton)
buttonRow.add(Box.createHorizontalStrut(6))
buttonRow.add(closeButton)

def bottom = new JPanel(new BorderLayout(10, 0))
bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8))
bottom.add(status, BorderLayout.CENTER)
bottom.add(buttonRow, BorderLayout.EAST)

def split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, centre, new JScrollPane(detailArea))
split.setResizeWeight(1.0d)
split.setDividerLocation(330)

def content = new JPanel(new BorderLayout())
content.add(split, BorderLayout.CENTER)
content.add(bottom, BorderLayout.SOUTH)

// Undo and redo while this window holds the focus, so the action you just
// identified can be undone without going back to the map first.
def rootPane = dialog.getRootPane()
def undoKey = { ActionEvent e -> if (undoHandler.canUndo()) undoHandler.undo() } as ActionListener
def redoKey = { ActionEvent e -> if (undoHandler.canRedo()) undoHandler.redo() } as ActionListener
rootPane.registerKeyboardAction(undoKey, KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK),
        JComponent.WHEN_IN_FOCUSED_WINDOW)
rootPane.registerKeyboardAction(redoKey, KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK),
        JComponent.WHEN_IN_FOCUSED_WINDOW)
rootPane.registerKeyboardAction(redoKey,
        KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
        JComponent.WHEN_IN_FOCUSED_WINDOW)
rootPane.registerKeyboardAction({ ActionEvent e -> dialog.dispose() } as ActionListener,
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)

/*
 * setLocationRelativeTo(mainFrame) is not safe here: when the Freeplane window
 * is maximized its getBounds() can report a rectangle that reaches outside the
 * virtual desktop, and the window then opens off screen -- present, visible,
 * and impossible to see. So: centre on the owner, but clamp to the screen that
 * actually holds that point.
 */
void placeOnScreen(Window window, Window owner) {
    Point centre = null
    if (owner != null) {
        Rectangle b = owner.getBounds()
        centre = new Point((int) (b.@x + b.@width / 2), (int) (b.@y + b.@height / 2))
    }
    GraphicsConfiguration gc = null
    GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().each { device ->
        GraphicsConfiguration candidate = device.getDefaultConfiguration()
        if (centre != null && candidate.getBounds().contains(centre)) gc = candidate
    }
    if (gc == null) {
        gc = MouseInfo.getPointerInfo()?.getDevice()?.getDefaultConfiguration()
        if (gc == null) gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration()
        Rectangle b = gc.getBounds()
        centre = new Point((int) (b.@x + b.@width / 2), (int) (b.@y + b.@height / 2))
    }

    Rectangle screen = gc.getBounds()
    Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    int availableWidth = screen.@width - insets.@left - insets.@right
    int availableHeight = screen.@height - insets.@top - insets.@bottom
    window.setSize(Math.min(window.getWidth(), availableWidth), Math.min(window.getHeight(), availableHeight))

    int minX = screen.@x + insets.@left
    int minY = screen.@y + insets.@top
    int x = Math.min(Math.max(centre.@x - (int) (window.getWidth() / 2), minX), minX + availableWidth - window.getWidth())
    int y = Math.min(Math.max(centre.@y - (int) (window.getHeight() / 2), minY), minY + availableHeight - window.getHeight())
    window.setLocation(x, y)
}

dialog.setContentPane(content)
dialog.setSize(new Dimension(960, 540))
placeOnScreen(dialog, UITools.getCurrentFrame())
dialog.setVisible(true)
dialog.toFront()

// Also say it in the status bar: with several monitors the window can open on
// the one you are not looking at, and then the script seems to do nothing.
Controller.getCurrentController().getViewController().out(
        "Undo stack of '${node.mindMap.name}': ${state.rows.size()} action(s)".toString())
table.requestFocusInWindow()
