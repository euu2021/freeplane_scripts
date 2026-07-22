// Copyright (c) 2026 euu2021
// SPDX-License-Identifier: GPL-2.0-or-later
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 2 of the License, or
// (at your option) any later version.

// Discussion thread: https://github.com/freeplane/freeplane/issues/2938

/*
 * Cross-map links - prototype (feature request freeplane/freeplane#2938)
 *
 * When triggered, scans every .mm file in the current map's folder (recursively)
 * and opens a window showing, for the CURRENT map:
 *   OUT     - nodes in this map that link to other maps
 *   IN      - nodes in other maps that link to this map (backlinks / "what links here")
 *   Friends - the other maps connected to this one (link neighbours), each openable
 *
 * This is the cross-map backlinks idea from issue #2938, done as an on-demand
 * script (no sidecar/index needed). Reading closed maps from disk mirrors what
 * i-plasm's MapCrawler (discussion #2344) already proves is feasible and fast.
 *
 * Prototype limitations (documented on purpose):
 *   - Reads maps from DISK, so unsaved changes in the current map are not reflected.
 *   - Node text of rich-text nodes shows a placeholder (only the TEXT attribute is read).
 *   - Whole-folder scan on the EDT. Fine for a modest folder; MapCrawler's off-EDT
 *     streaming is the productionization path for very large sets.
 */

import javax.swing.*
import javax.swing.event.*
import java.awt.*
import java.awt.event.*
import java.util.List
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import org.freeplane.features.mode.Controller as CoreController

// ---------------------------------------------------------------------------
// Pure core (testable without the map GUI)
// ---------------------------------------------------------------------------

/** Resolve a node LINK value to [file: canonical target .mm File, frag: target node id]
 *  or null if the link does not target a .mm file. */
def resolveTarget(File sourceFile, String link) {
    if (link == null || link.isEmpty()) return null
    int h = link.indexOf('#')
    String uriPart = (h >= 0) ? link.substring(0, h) : link
    String frag    = (h >= 0) ? link.substring(h + 1) : null
    if (!uriPart.toLowerCase().endsWith('.mm')) return null
    File f
    try {
        URI u = new URI(uriPart)
        if (u.isAbsolute() && u.scheme == 'file') {
            f = new File(u)
        } else {
            f = new File(sourceFile.parentFile, URLDecoder.decode(uriPart, 'UTF-8'))
        }
    } catch (ignored) {
        f = new File(sourceFile.parentFile, uriPart)
    }
    try { f = f.canonicalFile } catch (ignored) { }
    return [file: f, frag: frag]
}

/** Parse one .mm file, returning [links: <cross-map link records>, nodes: <id->text>]. */
Map scanFile(File f) {
    def links = []
    def nodes = [:]
    def factory = XMLInputFactory.newInstance()
    factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE)
    def stream = new FileInputStream(f)
    def rd = factory.createXMLStreamReader(stream)
    try {
        while (rd.hasNext()) {
            if (rd.next() == XMLStreamConstants.START_ELEMENT && rd.localName == 'node') {
                String id   = rd.getAttributeValue(null, 'ID')
                String text = rd.getAttributeValue(null, 'TEXT') ?: '(rich/none)'
                if (id != null) nodes[id] = text
                String link = rd.getAttributeValue(null, 'LINK')
                if (link == null) continue
                def t = resolveTarget(f, link)
                if (t == null) continue
                links << [
                    sourceFile    : f.canonicalFile,
                    sourceNodeId  : id,
                    sourceNodeText: text,
                    targetFile    : t.file,
                    targetNodeId  : t.frag,
                ]
            }
        }
    } finally {
        try { rd.close() } catch (ignored) { }
        try { stream.close() } catch (ignored) { }
    }
    return [links: links, nodes: nodes]
}

/** Scan the maps directly inside a folder (non-recursive); return
 *  [links: <all records>, nodeText: <'canonPath#id' -> text>].
 *  Scope = the folder passed in (the active map's folder). To also include
 *  subfolders, swap the listing below for: dir.eachFileRecurse { File it -> ... } */
Map scanFolder(File dir) {
    def links = []
    def nodeText = [:]
    def files = dir.listFiles()
    if (files == null) return [links: links, nodeText: nodeText]
    files.each { File it ->
        if (it.isFile() && it.name.toLowerCase().endsWith('.mm')) {
            try {
                def r = scanFile(it)
                links.addAll(r.links)
                String base = it.canonicalPath
                r.nodes.each { id, text -> nodeText[base + '#' + id] = text }
            } catch (ignored) { }
        }
    }
    return [links: links, nodeText: nodeText]
}

/** Text of a node given its file+id, or a graceful fallback. */
def nodeLabel(Map nodeText, File file, String id) {
    if (id == null) return null
    def t = nodeText[file.canonicalPath + '#' + id]
    return t ?: ('node ' + id)
}

/** Split the links into OUT (from current map) and IN (into current map). */
Map splitInOut(List links, File currentFile) {
    File cur = currentFile.canonicalFile
    def outRecs = links.findAll { it.sourceFile == cur }
    def inRecs  = links.findAll { it.targetFile == cur }
    return [out: outRecs, inn: inRecs]
}

/** Distinct other maps connected to the current map (link neighbours). */
List friendsOf(List outRecs, List inRecs, File currentFile) {
    File cur = currentFile.canonicalFile
    def files = []
    outRecs.each { files << it.targetFile }
    inRecs.each  { files << it.sourceFile }
    return files.findAll { it != cur }.unique { it.canonicalPath }.sort { it.name.toLowerCase() }
}

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------

def navigateTo(File targetFile, String nodeId) {
    if (!targetFile.exists()) {
        CoreController.currentController.viewController.out("Cross-map links: target not found - " + targetFile.absolutePath)
        return
    }
    try {
        def loader = c.mapLoader(targetFile).withView()
        if (nodeId) loader = loader.selectNodeById(nodeId)
        loader.getMindMap()
    } catch (Throwable t) {
        CoreController.currentController.viewController.out("Cross-map links: could not open " + targetFile.name + " - " + t.message)
    }
}

// ---------------------------------------------------------------------------
// Window
// ---------------------------------------------------------------------------

def mapLabel = { File f -> f == null ? '?' : f.name.replaceFirst(/(?i)\.mm$/, '') }

def esc = { String s -> s == null ? '' : s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;') }

def rowText = { Map rec, boolean isOut, Map nodeText ->
    if (isOut) {
        String tgt = nodeLabel(nodeText, rec.targetFile, rec.targetNodeId) ?: mapLabel(rec.targetFile)
        return "<html><b>" + esc(rec.sourceNodeText) + "</b> &rarr; " + esc(tgt) +
               " <font color='gray'>(" + esc(mapLabel(rec.targetFile)) + ")</font></html>"
    } else {
        String tgt = nodeLabel(nodeText, rec.targetFile, rec.targetNodeId) ?: mapLabel(rec.targetFile)
        return "<html><font color='gray'>(" + esc(mapLabel(rec.sourceFile)) + ")</font> " +
               esc(rec.sourceNodeText) + " &rarr; <b>" + esc(tgt) + "</b></html>"
    }
}

def buildLinkList = { List recs, boolean isOut, Map nodeText ->
    def model = new DefaultListModel()
    recs.each { model.addElement(it) }
    def list = new JList(model)
    list.cellRenderer = new DefaultListCellRenderer() {
        Component getListCellRendererComponent(JList l, Object value, int i, boolean sel, boolean foc) {
            super.getListCellRendererComponent(l, value, i, sel, foc)
            setText(rowText(value, isOut, nodeText))
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6))
            return this
        }
    }
    list.addMouseListener(new MouseAdapter() {
        void mousePressed(MouseEvent e) {
            if (e.clickCount == 2) {
                def rec = list.selectedValue
                if (rec == null) return
                if (isOut) navigateTo(rec.targetFile, rec.targetNodeId)
                else       navigateTo(rec.sourceFile, rec.sourceNodeId)
            }
        }
    })
    return list
}

def sectionPanel = { String title, JComponent body ->
    def p = new JPanel(new BorderLayout())
    def h = new JLabel(title)
    h.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8))
    h.setFont(h.getFont().deriveFont(Font.BOLD))
    p.add(h, BorderLayout.NORTH)
    p.add(body, BorderLayout.CENTER)
    return p
}

// The Friends buttons "View" the neighbour's links inside THIS window (no map is opened).
def buildFriendsPanel = { List friends, Closure viewInWindow ->
    def panel = new JPanel()
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS))
    panel.setBackground(Color.WHITE)
    friends.each { File f ->
        def row = new JPanel(new BorderLayout(8, 0))
        row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6))
        row.setBackground(Color.WHITE)
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34))
        row.add(new JLabel(mapLabel(f)), BorderLayout.CENTER)
        def view = new JButton('View')
        view.setToolTipText('Show this map\'s links here, without opening it')
        view.addActionListener({ viewInWindow(f) } as ActionListener)
        row.add(view, BorderLayout.EAST)
        panel.add(row)
    }
    panel.add(Box.createVerticalGlue())
    return panel
}

def showBrowser = { File startFile, List links, Map nodeText ->
    def frame = Window.windows.find { it.showing && it instanceof JFrame }
    // idempotent: dispose a previous window
    Window.windows.each { w ->
        if (w instanceof JDialog && w.getName() == 'CrossMapLinksWindow') { w.dispose() }
    }
    def dlg = new JDialog(frame, 'Cross-map links', false)
    dlg.setName('CrossMapLinksWindow')

    def viewingLabel = new JLabel()
    viewingLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8))
    def center = new JPanel(new GridLayout(3, 1, 0, 6))

    // re-render the three sections for any map in the scanned scope, from the in-memory
    // index only - the map is NOT opened in Freeplane.
    Closure render
    render = { File cur ->
        def io = splitInOut(links, cur)
        def friends = friendsOf(io.out, io.inn, cur)
        dlg.setTitle('Cross-map links — ' + mapLabel(cur))
        viewingLabel.setText('<html>Viewing links of <b>' + esc(mapLabel(cur)) + '</b></html>')
        center.removeAll()
        center.add(sectionPanel("OUT — this map links to (" + io.out.size() + ")",
            new JScrollPane(buildLinkList(io.out, true, nodeText))))
        center.add(sectionPanel("IN — what links here (" + io.inn.size() + ")",
            new JScrollPane(buildLinkList(io.inn, false, nodeText))))
        center.add(sectionPanel("Friends — connected maps (" + friends.size() + ")",
            new JScrollPane(buildFriendsPanel(friends, render))))
        center.revalidate()
        center.repaint()
    }

    def hint = new JLabel('  Double-click a link to open the map and jump to the node. "View" browses a map here, without opening it.')
    hint.setForeground(Color.GRAY)
    hint.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4))

    render(startFile)

    dlg.layout = new BorderLayout()
    dlg.add(viewingLabel, BorderLayout.NORTH)
    dlg.add(center, BorderLayout.CENTER)
    dlg.add(hint, BorderLayout.SOUTH)
    dlg.setSize(720, 820)
    dlg.setLocationRelativeTo(frame)
    dlg.setVisible(true)
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

def curFile = node.mindMap.file
if (curFile == null) {
    CoreController.currentController.viewController.out('Cross-map links: the current map has no file on disk - save it first.')
    return
}
def scan = scanFolder(curFile.parentFile)
showBrowser(curFile, scan.links, scan.nodeText)
def io = splitInOut(scan.links, curFile)
"OUT=" + io.out.size() + " IN=" + io.inn.size() + " (scanned " + scan.links.size() + " cross-map links)"
