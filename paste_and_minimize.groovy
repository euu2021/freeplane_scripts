// SPDX-License-Identifier: CC0-1.0
// Discussion thread: https://sourceforge.net/p/freeplane/discussion/758437/thread/e9200db6c3/

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
