// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Version: 1.0.0

c.select(c.selecteds.collect{it.children.findAll{it.visible}}.flatten())
