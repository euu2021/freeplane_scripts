// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/issues/2795#issuecomment-3700079936

// Copy Node ID with Hashtag
// Copies the selected node's ID to clipboard with "#" prepended for easy hyperlink pasting

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// Get the selected node's ID
def nodeId = node.id

// Prepend hashtag to make it a valid internal hyperlink
def hyperlinkText = "#${nodeId}"

// Copy to clipboard
def stringSelection = new StringSelection(hyperlinkText)
def clipboard = Toolkit.getDefaultToolkit().getSystemClipboard()
clipboard.setContents(stringSelection, null)

// Show confirmation message
c.statusInfo = "Copied to clipboard: ${hyperlinkText}"
