// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://sourceforge.net/p/freeplane/discussion/758437/thread/e9200db6c3/
// Version: 1.1

/**
 * Pastes the clipboard and minimizes the nodes that came in longer than
 * `max_shortened_text_length`.
 *
 * Consider `autoMinimizerListener.groovy` instead: it does the same thing as a
 * listener, so it covers every way a node can appear -- typing, pasting, importing,
 * nodes created by a script or by the AI plugin, a map being opened -- instead of only
 * this one action, and it does not need a shortcut of its own or replace the native
 * paste. This script is kept for whoever prefers a single explicit action.
 *
 * Note that it finds the pasted nodes by looking at what was created in the last 4
 * seconds, so a slow paste of a very large branch may leave the tail untouched.
 *
 * CHANGELOG
 * ---------
 *   1.1 (2026-07-27)
 *       Header note pointing to autoMinimizerListener. No behaviour change.
 *   1.0 (2026-07-20)
 *       First public version.
 */

//// @ExecutionModes({ON_SINGLE_NODE})

menuUtils.executeMenuItems(['PasteAction',])

def max_shortened_text_length = config.getIntProperty("max_shortened_text_length") 

def createdSince = new Date()
createdSince.setSeconds(createdSince.getSeconds() - 4);

def matches = new ArrayList(c.find{ it.CreatedAt.after(createdSince) })
matches.each{
it.sideAtRoot = 'BOTTOM_OR_RIGHT'
if (it.to.plain.size() > max_shortened_text_length)         it.setMinimized(true)     
else         it.setMinimized(false) }
