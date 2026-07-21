// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://sourceforge.net/p/freeplane/discussion/758437/thread/00a59ea0d6/

def n = node
def inCons = n.connectorsIn //collection of connectors pointing into the node
def outCons = n.connectorsOut //collection of connectors pointing out of the node
def totCons = inCons + outCons // all the node's connectors
c.select(totCons.target + totCons.source)
