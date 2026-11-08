/* Modifications Copyright (C) 2026 Ettacent */

/*
 * Copyright the Collabora Online contributors.
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Adapted from CollaboraOnline/online.mirror, commit
 * 2608b882b757418d9af69af2c575a1a27e8025ba,
 * browser/src/app/BitmapTileManager.ts: sortTileKeysByDistance,
 * garbageCollect, setBitmapOnTile, sortTileBitmapList, getExpiryFactor,
 * reclaimTileBitmapMemory and removeTile.
 * Modifications Copyright (C) 2026 Ettacent.
 */
(() => {
'use strict';
class OfficeTileCache {
  constructor(hooks, {maxBytes = 20 * 1024 * 1024, maxCount = 4} = {}) {
    if (!Number.isSafeInteger(maxBytes) || maxBytes < 1 ||
        !Number.isSafeInteger(maxCount) || maxCount < 1) throw RangeError('tile cache limits');
    this.hooks = hooks;
    this.maxBytes = maxBytes;
    this.maxCount = maxCount;
    this.tiles = new Map();
    this.tileBitmapList = [];
    this.bytes = 0;
  }

  sortTileKeysByDistance() {
    return Array.from(this.tiles.keys()).sort((a, b) =>
      this.tiles.get(b).distanceFromView - this.tiles.get(a).distanceFromView);
  }

  sortTileBitmapList() {
    this.tileBitmapList.sort((a, b) => a.distanceFromView - b.distanceFromView);
  }

  getExpiryFactor(tile) {
    return this.tileBitmapList.indexOf(tile) / Math.max(this.tileBitmapList.length, 1);
  }

  removeTile(key) {
    const tile = this.tiles.get(key);
    if (!tile) return;
    this.tiles.delete(key);
    const index = this.tileBitmapList.indexOf(tile);
    if (index !== -1) this.tileBitmapList.splice(index, 1);
    this.bytes -= tile.bytes;
    this.hooks.dispose(tile);
  }

  reconcile() {
    for (const [key, tile] of this.tiles) {
      if (!this.hooks.valid(tile)) {
        this.removeTile(key);
        continue;
      }
      tile.distanceFromView = this.hooks.distance(tile);
      if (!Number.isFinite(tile.distanceFromView) || tile.distanceFromView < 0) {
        this.removeTile(key);
      }
    }
    this.sortTileBitmapList();
  }

  garbageCollect(byteLimit = this.maxBytes, countLimit = this.maxCount) {
    if (!(Number.isFinite(byteLimit) && Number.isFinite(countLimit))) return false;
    byteLimit = Math.max(0, Math.min(this.maxBytes, byteLimit));
    countLimit = Math.max(0, Math.min(this.maxCount, countLimit));
    if (this.bytes <= byteLimit && this.tiles.size <= countLimit) return true;
    const keys = this.sortTileKeysByDistance();
    for (const key of keys) {
      if (this.bytes <= byteLimit && this.tiles.size <= countLimit) break;
      if (this.tiles.get(key).distanceFromView !== 0) this.removeTile(key);
    }
    return this.bytes <= byteLimit && this.tiles.size <= countLimit;
  }

  capacityBytes(replace = () => false) {
    let bytes = this.maxBytes, count = this.maxCount;
    for (const tile of this.tiles.values()) if (this.hooks.distance(tile) === 0 && !replace(tile)) {
      bytes -= tile.bytes; count--;
    }
    return count > 0 ? Math.max(0, bytes) : 0;
  }

  planInsertion(key, input, replace = () => false) {
    if (this.tiles.has(key) || !Number.isSafeInteger(input.bytes) || input.bytes < 1 ||
        input.bytes > this.maxBytes || !this.hooks.valid(input)) return null;
    const distance = this.hooks.distance(input);
    if (!Number.isFinite(distance) || distance < 0) return null;
    let bytes = this.bytes + input.bytes, count = this.tiles.size + 1;
    const victims = [];
    for (const [oldKey, old] of this.tiles) if (this.hooks.distance(old) === 0 && replace(old)) {
      victims.push(oldKey); bytes -= old.bytes; count--;
    }
    const candidates = [...this.tiles].filter(([, tile]) => this.hooks.distance(tile) > 0)
      .sort((a, b) => this.hooks.distance(b[1]) - this.hooks.distance(a[1]));
    for (const [oldKey, old] of candidates) {
      if (bytes <= this.maxBytes && count <= this.maxCount) break;
      victims.push(oldKey); bytes -= old.bytes; count--;
    }
    if (bytes > this.maxBytes || count > this.maxCount) return null;
    return {distance, victims, bytes};
  }

  setBitmapOnTile(key, input, replace = () => false) {
    this.reconcile();
    const plan = this.planInsertion(key, input, replace);
    if (!plan) return false;
    const {distance, victims} = plan;
    const tile = {...input, distanceFromView: distance};
    const nextTiles = new Map(this.tiles);
    const nextList = this.tileBitmapList.filter(old => !victims.some(key => this.tiles.get(key) === old));
    for (const oldKey of victims) nextTiles.delete(oldKey);
    if (distance === 0) nextList.unshift(tile);
    else {
      let low = 0;
      let high = nextList.length;
      while (low < high) {
        const mid = Math.floor((low + high) / 2);
        if (nextList[mid].distanceFromView < distance) low = mid + 1;
        else high = mid;
      }
      nextList.splice(low, 0, tile);
    }
    nextTiles.set(key, tile);
    if (!this.hooks.valid(input)) return false;
    const retired = victims.map(oldKey => this.tiles.get(oldKey));
    this.tiles = nextTiles; this.tileBitmapList = nextList; this.bytes = plan.bytes;
    for (const old of retired) this.hooks.dispose(old);
    return true;
  }

  find(predicate) {
    return this.tileBitmapList.find(predicate);
  }

  values() { return this.tiles.values(); }
  get size() { return this.tiles.size; }
  clear() {
    for (const key of Array.from(this.tiles.keys())) this.removeTile(key);
  }
}
globalThis.OfficeTileCache = OfficeTileCache;
})();
