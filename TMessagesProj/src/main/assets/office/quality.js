/* Modifications Copyright (C) 2026 Ettacent */
(() => {
  'use strict';











  function regionSupport(viewer) {
    try {
      const doc = viewer?._doc,s = viewer?._scroller;
      if (typeof doc?.getRegionRasterCapabilities !== 'function' || typeof doc.renderPageToBitmap !== 'function' ||
      typeof s?.observeDetailIdle !== 'function') return false;
      const cap = doc.getRegionRasterCapabilities();
      return cap?.version === 1 && cap.maxPixels === 4000000 && cap.maxAxis === 4096 &&
      cap.resourceDensity === true && cap.simpleResourcesOnly === true &&
      s.slots instanceof Map && s.renderQueue instanceof Map && s.staged instanceof Map &&
      typeof s.retainedBytes === 'function' && typeof s.pump === 'function' &&
      typeof s.renderActive === 'boolean' && typeof s.pumpPosted === 'boolean' &&
      Number.isFinite(s.renderEpoch) && Number.isFinite(s.rasterReservation) && Number.isFinite(s.rasterBudget) &&
      typeof viewer._pageWidthPx === 'function' && typeof viewer._pageHeightPx === 'function' &&
      typeof viewer.getScale === 'function' && !!viewer._scrollHost;
    } catch {return false;}
  }
  /* Copyright 2012 Mozilla Foundation
   *
   * Licensed under the Apache License, Version 2.0 (the "License");
   * you may not use this file except in compliance with the License.
   * You may obtain a copy of the License at
   *
   *     http://www.apache.org/licenses/LICENSE-2.0
   *
   * Unless required by applicable law or agreed to in writing, software
   * distributed under the License is distributed on an "AS IS" BASIS,
   * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   * See the License for the specific language governing permissions and
   * limitations under the License.
   */

  /* Adapted from PDFPageDetailView, Mozilla PDF.js 24a63a4d488d4fe26c2a5ca32216ac9698d0de59.
   * Modifications Copyright (C) 2026 Ettacent.
   */
  function officeDetailAreaChanged(detailArea, visibleArea, viewport) {
    if (!detailArea) {
      return true;
    }

    const minDetailX = detailArea.minX;
    const minDetailY = detailArea.minY;
    const maxDetailX = detailArea.width + minDetailX;
    const maxDetailY = detailArea.height + minDetailY;

    if (
    visibleArea.minX < minDetailX ||
    visibleArea.minY < minDetailY ||
    visibleArea.maxX > maxDetailX ||
    visibleArea.maxY > maxDetailY)
    {
      return true;
    }

    const {
      width: maxWidth,
      height: maxHeight,
      scale
    } = viewport;

    if (detailArea.scale !== scale) {
      return true;
    }

    const paddingLeftSize = visibleArea.minX - minDetailX;
    const paddingRightSize = maxDetailX - visibleArea.maxX;
    const paddingTopSize = visibleArea.minY - minDetailY;
    const paddingBottomSize = maxDetailY - visibleArea.maxY;


    const MOVEMENT_THRESHOLD = 0.5;
    const ratio = (1 + MOVEMENT_THRESHOLD) / MOVEMENT_THRESHOLD;

    if (
    minDetailX > 0 && paddingRightSize / paddingLeftSize > ratio ||
    maxDetailX < maxWidth && paddingLeftSize / paddingRightSize > ratio ||
    minDetailY > 0 && paddingBottomSize / paddingTopSize > ratio ||
    maxDetailY < maxHeight && paddingTopSize / paddingBottomSize > ratio)
    {
      return true;
    }

    return false;
  }

  function officeDetailArea(visibleArea, viewport, maxCanvasPixels, pixelRatio) {
    const visibleWidth = visibleArea.maxX - visibleArea.minX;
    const visibleHeight = visibleArea.maxY - visibleArea.minY;

    const visiblePixels =
    visibleWidth * visibleHeight * pixelRatio ** 2;
    const maxDetailToVisibleLinearRatio = Math.sqrt(
      maxCanvasPixels /
      visiblePixels
    );
    const maxOverflowScale = (maxDetailToVisibleLinearRatio - 1) / 2;
    let overflowScale = Math.min(1, maxOverflowScale);
    if (overflowScale < 0) {
      overflowScale = 0;
    }

    const overflowWidth = visibleWidth * overflowScale;
    const overflowHeight = visibleHeight * overflowScale;

    const minX = Math.max(0, visibleArea.minX - overflowWidth);
    const maxX = Math.min(viewport.width, visibleArea.maxX + overflowWidth);
    const minY = Math.max(0, visibleArea.minY - overflowHeight);
    const maxY = Math.min(viewport.height, visibleArea.maxY + overflowHeight);
    const width = maxX - minX;
    const height = maxY - minY;

    return { minX, minY, width, height, scale: viewport.scale };

  }

  const DETAIL_BUDGET = 48 * 1024 * 1024;
  const IDENTITY = ['owner', 'scroller', 'slot', 'unit', 'revision', 'rasterEpoch', 'generation', 'theme',
  'scale', 'pageWidth', 'pageHeight', 'x', 'y', 'width', 'height', 'dpr', 'resourceDpr', 'minimumDpr',
  'baseCanvas', 'baseWidth', 'baseHeight', 'officeReadingPalette', 'imageResources', 'defaultTextColor', 'currentDate', 'showTrackedChanges'];
  class DetailController {
    constructor(hooks) {
      this.hooks = hooks;this.epoch = 0;this.targets = new Map();this.pending = null;
      this.closed = false;this.paused = false;this.sequence = 0;
    }
    same(a, b) {return !!a && !!b && IDENTITY.every((k) => Object.is(a[k], b[k]));}
    get wanted() {return [...this.targets.values()].some((e) => e.status === 'queued');}
    request(request) {return this.requestAll([request]);}
    requestAll(requests) {
      if (this.closed || this.paused) return false;
      const next = new Map();
      for (const input of requests) {
        const snapshot = Object.freeze(Object.fromEntries([...IDENTITY, 'priority'].map((k) => [k, input[k]])));
        const key = snapshot.slot ?? snapshot.unit,old = this.targets.get(key);
        next.set(key, this.same(old?.request, snapshot) && old.status !== 'stale' ? old : { request: snapshot, status: 'queued',
          sequence: old?.sequence ?? ++this.sequence, epoch: ++this.epoch });
      }
      this.targets = next;this.pump();return this.wanted || !!this.pending;
    }
    invalidate() {this.epoch++;this.targets.clear();}
    forget(key) {this.targets.delete(key);}
    pause() {this.paused = true;this.invalidate();}
    resume() {if (!this.closed) this.paused = false;}
    close() {this.closed = true;this.invalidate();}
    notifyIdle() {
      for (const entry of this.targets.values()) if (entry.status === 'budget') entry.status = 'queued';
      this.pump();
    }
    current(job) {
      const entry = this.targets.get(job.slot ?? job.unit);
      try {
        const budget = Math.min(DETAIL_BUDGET, this.hooks.budget()),used = this.hooks.retained();
        return !this.closed && !this.paused && entry === job.entry && Number.isFinite(budget) && Number.isFinite(used) &&
        used >= 0 && used + job.reservationBytes <= budget && this.hooks.current(job);
      }
      catch {return false;}
    }
    admit(request) {
      const rawBudget = this.hooks.budget(),used = this.hooks.retained();
      const { width, height, dpr } = request,resource = request.resourceDpr ?? 0;
      const minimum = Math.max(request.minimumDpr ?? 0, resource);
      if (![rawBudget, used, width, height, dpr, minimum, resource].every(Number.isFinite) || used < 0 || resource < 0 ||
      width <= 0 || height <= 0 || dpr <= 0 || minimum < 0) return null;
      const budget = Math.min(DETAIL_BUDGET, rawBudget),pixels = Math.min(4000000, Math.floor((budget - used) / 12), this.hooks.capacityPixels?.(request) ?? 4000000);
      if (pixels < 1) return null;
      const fits = (d) => Math.ceil(width * d) >= 1 && Math.ceil(height * d) >= 1 && Math.ceil(width * d) <= 4096 && Math.ceil(height * d) <= 4096 &&
      Math.ceil(width * d) * Math.ceil(height * d) <= pixels;
      let density = Math.min(dpr, 4096 / width, 4096 / height, Math.sqrt(pixels / (width * height)));
      if (!fits(density)) {
        let lower = 0,upper = density;
        for (let i = 0; i < 48; i++) {const middle = (lower + upper) / 2;if (fits(middle)) lower = middle;else upper = middle;}
        density = lower;
      }
      if (!(density > minimum) || !fits(density)) return null;
      const widthPixels = Math.ceil(width * density),heightPixels = Math.ceil(height * density);
      return { dpr: density, widthPixels, heightPixels, reservationBytes: widthPixels * heightPixels * 12 };
    }
    pump() {
      if (this.closed || this.paused || this.pending || !this.wanted) return;
      try {if (!this.hooks.idle()) return;} catch {return;}
      const entries = [...this.targets.values()].filter((e) => e.status === 'queued').sort((a, b) => a.sequence - b.sequence);
      for (const entry of entries) {
        let admitted;
        try {admitted = this.admit(entry.request);} catch {admitted = null;}
        if (!admitted) {entry.status = 'budget';continue;}
        const job = Object.freeze({ ...entry.request, ...admitted, original: entry.request, entry, epoch: entry.epoch });
        if (!this.current(job)) {entry.status = 'stale';continue;}
        let lease;
        try {lease = this.hooks.acquire(job);} catch (error) {
          entry.status = 'failed';try {this.hooks.failure?.(error, job);} catch {}continue;
        }
        if (!lease) return;
        entry.status = 'pending';entry.sequence = ++this.sequence;this.pending = job;
        this.run(job, lease);return;
      }
    }
    async run(job, lease) {

      {
        let bitmap;
        try {
          if (!this.current(job)) return;
          bitmap = await this.hooks.render(job, () => this.current(job));
          if (!bitmap || !this.current(job)) return;
          const published = this.hooks.install(job, bitmap);job.entry.status = published === false ? 'budget' : 'complete';
        } catch (error) {
          job.entry.status = 'failed';
          if (this.current(job)) try {this.hooks.failure?.(error, job);} catch {}
        } finally {
          if (job.entry.status === 'pending') job.entry.status = 'stale';
          try {bitmap?.close();} catch {}
          try {lease.release();} catch {}
          if (this.pending === job) this.pending = null;
          if (!this.closed && !this.paused && this.wanted) try {this.hooks.schedule(() => this.pump());} catch {}
        }
      }

    }
  }

  function attachAdapter(viewer, DetailController, options = {}) {
    const tiles = new OfficeTileCache({
      valid: (tile) => valid(tile.job, true, true) && tile.canvas.parentNode === tile.job.slot.wrapper &&
      tile.job.dpr > baseDensity(tile.job.slot, tile.job.pageWidth, tile.job.pageHeight),
      distance: (tile) => {
        if (tileVisible(tile.job)) return 0;
        const r = tile.job.slot.wrapper.getBoundingClientRect(),vp = viewport();
        const x = r.left + (tile.job.x + tile.job.width / 2) / tile.job.pageWidth * r.width;
        const y = r.top + (tile.job.y + tile.job.height / 2) / tile.job.pageHeight * r.height;
        return Math.max(1, Math.hypot(x - (vp.left + vp.right) / 2, y - (vp.top + vp.bottom) / 2));
      },
      dispose: (tile) => remove(tile.canvas)
    });
    let closed = false,paused = false,interaction = false,generation = 0,leaseOwner = null,installed = 0,failed = 0,lastJob = null;
    const unsupported = new Map();
    const interacting = () => interaction || !!options.interacting?.();
    const theme = () => options.themeToken?.();
    const baseDensity = (slot, width, height) => Math.min(slot.canvas.width / width, slot.canvas.height / height);
    const remove = (canvas) => {canvas.remove();canvas.width = 0;canvas.height = 0;};
    const viewport = () => {
      const host = viewer._scrollHost,rect = host.getBoundingClientRect();
      const left = rect.left + (host.clientLeft || 0),top = rect.top + (host.clientTop || 0);
      return { left: Math.max(0, left), top: Math.max(0, top),
        right: Math.min(typeof innerWidth === 'number' ? innerWidth : Infinity, host.clientWidth ? left + host.clientWidth : rect.right),
        bottom: Math.min(typeof innerHeight === 'number' ? innerHeight : Infinity, host.clientHeight ? top + host.clientHeight : rect.bottom) };
    };
    const visible = (slot, vp) => {const r = slot.wrapper.getBoundingClientRect();return r.right > vp.left && r.left < vp.right && r.bottom > vp.top && r.top < vp.bottom;};
    const tileVisible = (job) => {
      const r = job.slot.wrapper.getBoundingClientRect(),vp = viewport();
      const left = r.left + job.x / job.pageWidth * r.width,top = r.top + job.y / job.pageHeight * r.height;
      return left < vp.right && top < vp.bottom && left + job.width / job.pageWidth * r.width > vp.left &&
      top + job.height / job.pageHeight * r.height > vp.top;
    };
    const reportFailure = (error, job) => {
      failed++;
      if (error?.code === 'docx-caller-input') unsupported.set(job.unit, { owner: job.owner, revision: job.revision,
        theme: job.theme, imageResources: job.imageResources, defaultTextColor: job.defaultTextColor,
        currentDate: job.currentDate, showTrackedChanges: job.showTrackedChanges });
    };
    const valid = (job, allowInteraction = false, cached = false) => {
      const s = job.scroller,slot = job.slot;
      return !closed && !paused && !viewer._destroyed && !s._destroyed && regionSupport(viewer) && viewer._doc === job.owner &&
      viewer._scroller === s && generation === job.generation && Object.is(theme(), job.theme) && (
      viewer._renderEpoch === job.revision && s.renderEpoch === job.rasterEpoch || allowInteraction && interacting()) &&
      viewer.getScale() === job.scale &&
      viewer._opts?.officeReadingPalette === job.officeReadingPalette &&
      viewer._opts?.imageResources === job.imageResources && viewer._opts?.defaultTextColor === job.defaultTextColor &&
      viewer._currentDate === job.currentDate && !!viewer._showTrackedChanges === job.showTrackedChanges &&
      viewer._pageWidthPx(job.unit) === job.pageWidth && viewer._pageHeightPx(job.unit) === job.pageHeight &&
      s.slots.get(job.unit) === slot && slot.wrapper.isConnected && slot.canvas === job.baseCanvas &&
      slot.canvas.width === job.baseWidth && slot.canvas.height === job.baseHeight &&
      slot.wrapper.getAttribute('data-office-unit') === String(job.unit) &&
      slot.canvas.getAttribute('data-office-valid-unit') === String(job.unit) &&
      Number(slot.canvas.getAttribute('data-office-painted-scale')) === job.scale &&
      slot.canvas.getAttribute('data-office-painted-unit') === String(job.unit) &&
      job.resourceDpr <= baseDensity(slot, job.pageWidth, job.pageHeight) && (
      allowInteraction || !s.interacting && !interacting()) && (cached || visible(slot, viewport()));
    };
    function retire() {
      tiles.reconcile();
      tiles.garbageCollect();
    }
    const replacementCovered = (tile, job) => {
      if (tile.job.slot !== job.slot) return false;
      const rect = job.slot.wrapper.getBoundingClientRect(),vp = viewport(),old = tile.job;
      const left = Math.max(old.x, (vp.left - rect.left) * job.pageWidth / rect.width);
      const top = Math.max(old.y, (vp.top - rect.top) * job.pageHeight / rect.height);
      const right = Math.min(old.x + old.width, (vp.right - rect.left) * job.pageWidth / rect.width);
      const bottom = Math.min(old.y + old.height, (vp.bottom - rect.top) * job.pageHeight / rect.height);
      return left >= right || top >= bottom || job.x <= left + 1e-6 && job.y <= top + 1e-6 &&
      job.x + job.width >= right - 1e-6 && job.y + job.height >= bottom - 1e-6;
    };
    const idle = (s) => !closed && !paused && !viewer._destroyed && !s._destroyed && !s.interacting && !s.renderActive &&
    !interacting() && !s.renderQueue.size && !s.staged.size && !s.pumpPosted && !s.rasterReservation;
    const controller = new DetailController({
      idle: () => idle(viewer._scroller),
      budget: () => Math.min(48 * 1024 * 1024, viewer._scroller.rasterBudget),
      capacityPixels: (job) => Math.floor(tiles.capacityBytes((tile) => replacementCovered(tile, job)) / 4),
      retained: () => viewer._scroller.retainedBytes(),
      acquire(job) {
        const s = job.scroller;if (!idle(s) || leaseOwner || !valid(job)) return null;
        const saved = { active: s.renderActive, reservation: s.rasterReservation },token = {};
        try {s.renderActive = true;s.rasterReservation = job.reservationBytes;leaseOwner = token;}
        catch (error) {try {s.renderActive = saved.active;} finally {s.rasterReservation = saved.reservation;}throw error;}
        return { release() {
            if (leaseOwner !== token) return;leaseOwner = null;
            try {s.renderActive = saved.active;} finally {
              s.rasterReservation = saved.reservation;
              if (!s._destroyed && !viewer._destroyed && viewer._scroller === s) s.pump();
            }
          } };
      },
      current: valid,
      async render(job, current) {
        if (!regionSupport(viewer) || !valid(job)) throw Error('region capability changed');
        const policy = job.imageResources;
        const upgrade = !!viewer._doc && job.owner.getRegionRasterCapabilities?.().simpleImageQuality === true &&
        policy?.strategy === 'adaptive' && policy?.resolution === 'display' &&
        Number.isSafeInteger(policy.decodedByteBudget) && policy.decodedByteBudget >= 4 && policy.decodedByteBudget <= 16 * 1024 * 1024;
        const request = { width: job.pageWidth, dpr: job.dpr, officeReadingPalette: job.officeReadingPalette,
          region: { x: job.x, y: job.y, width: job.width, height: job.height },
          regionSimpleImageQuality: upgrade, regionResourceDpr: Math.min(job.resourceDpr, job.dpr), imageResources: job.imageResources,
          defaultTextColor: job.defaultTextColor, currentDate: job.currentDate,
          ...(job.showTrackedChanges ? { showTrackedChanges: true } : {}) };
        if (job.owner._mode === 'main') request.officeIsCurrent = current;
        let result;
        if (job.owner._mode === 'main' && typeof OffscreenCanvas === 'undefined') {
          const source = document.createElement('canvas');source.width = source.height = 1;
          source.close = () => {source.width = source.height = 0;};
          try {if (current()) await job.owner.renderPage(source, job.unit, request);result = source;}
          catch (error) {source.close();throw error;}
        } else result = await job.owner.renderPageToBitmap(job.unit, request);
        return result;
      },
      install(job, bitmap) {
        if (!valid(job)) return false;
        if (bitmap.width !== job.widthPixels || bitmap.height !== job.heightPixels) throw Error('unexpected region surface');
        const canvas = document.createElement('canvas');
        try {
          canvas.width = bitmap.width;canvas.height = bitmap.height;
          canvas.setAttribute('aria-hidden', 'true');canvas.setAttribute('data-office-valid-unit', String(job.unit));canvas.setAttribute('data-office-detail', '');
          canvas.style.cssText = 'position:absolute;pointer-events:none;left:' + job.x / job.pageWidth * 100 + '%;top:' + job.y / job.pageHeight * 100 + '%;width:' + job.width / job.pageWidth * 100 + '%;height:' + job.height / job.pageHeight * 100 + '%;';
          const direct = typeof ImageBitmap === 'function' && bitmap instanceof ImageBitmap ? canvas.getContext('bitmaprenderer') : null;
          if (direct) {
            if (typeof direct.transferFromImageBitmap !== 'function') throw Error('detail bitmap transfer unavailable');
            direct.transferFromImageBitmap(bitmap);
          } else {
            const context = canvas.getContext('2d');if (!context) throw Error('detail canvas unavailable');
            context.drawImage(bitmap, 0, 0);
          }
          if (!valid(job)) {remove(canvas);return false;}
          job.slot.canvas.after(canvas);
          let previous = job.slot.canvas;
          for (const tile of [...tiles.values(), { canvas, job }].filter((tile) => tile.job.slot === job.slot).sort((a, b) => a.job.dpr - b.job.dpr)) {
            previous.after(tile.canvas);previous = tile.canvas;
          }
          if (!valid(job) || !tiles.setBitmapOnTile(canvas, { canvas, job, bytes: canvas.width * canvas.height * 4 }, (old) => replacementCovered(old, job))) {
            remove(canvas);return false;
          }
          installed++;lastJob = Object.fromEntries(Object.entries(job).filter(([k]) =>
          !['owner', 'scroller', 'slot', 'baseCanvas', 'original', 'entry', 'theme', 'imageResources', 'currentDate'].includes(k)));
        } catch (error) {remove(canvas);throw error;}
      },
      failure: reportFailure,
      schedule(callback) {if (!closed && !paused) (options.schedule || requestAnimationFrame)(callback);}
    });
    function request() {

      {
        if (closed || paused) return false;
        retire();
        const s = viewer._scroller;
        if (viewer._destroyed || s._destroyed || s.interacting || interacting() || !regionSupport(viewer)) {controller.requestAll([]);return false;}
        const vp = viewport(),requests = [],dpr = options.dpr ?? 3,margin = options.margin ?? 64;
        if (!Number.isFinite(dpr) || dpr <= 0 || !Number.isFinite(margin) || margin < 0) {controller.requestAll([]);return false;}
        for (const [unit, slot] of s.slots) {
          const rect = slot.wrapper.getBoundingClientRect(),left = Math.max(vp.left, rect.left),right = Math.min(vp.right, rect.right);
          const top = Math.max(vp.top, rect.top),bottom = Math.min(vp.bottom, rect.bottom);
          if (!(right > left && bottom > top && rect.width > 0 && rect.height > 0)) continue;
          const pageWidth = viewer._pageWidthPx(unit),pageHeight = viewer._pageHeightPx(unit);
          if (!(Number.isFinite(pageWidth) && pageWidth > 0 && Number.isFinite(pageHeight) && pageHeight > 0)) continue;
          const resourceDpr = baseDensity(slot, pageWidth, pageHeight);
          if (!(resourceDpr > 0) || resourceDpr >= dpr * .98) continue;
          const visibleArea = { minX: Math.max(0, (left - rect.left) * pageWidth / rect.width),
            maxX: Math.min(pageWidth, (right - rect.left) * pageWidth / rect.width),
            minY: Math.max(0, (top - rect.top) * pageHeight / rect.height),
            maxY: Math.min(pageHeight, (bottom - rect.top) * pageHeight / rect.height) };
          const areaViewport = { width: pageWidth, height: pageHeight, scale: viewer.getScale() };
          const cached = tiles.find((tile) => tile.job.slot === slot && valid(tile.job, true, true) && tile.job.original?.dpr === dpr && !officeDetailAreaChanged({
            minX: tile.job.x, minY: tile.job.y, width: tile.job.width, height: tile.job.height, scale: tile.job.scale
          }, visibleArea, areaViewport));
          if (cached && valid(cached.job, true) && cached.job.original?.dpr === dpr && !officeDetailAreaChanged({
            minX: cached.job.x, minY: cached.job.y, width: cached.job.width, height: cached.job.height, scale: cached.job.scale
          }, visibleArea, areaViewport)) continue;
          const requested = (visibleArea.maxX - visibleArea.minX + 2 * margin) * (visibleArea.maxY - visibleArea.minY + 2 * margin) * dpr * dpr;
          const visiblePixels = Math.min(4000000,
          (Math.ceil(visibleArea.maxX * dpr) - Math.floor(visibleArea.minX * dpr)) * (
          Math.ceil(visibleArea.maxY * dpr) - Math.floor(visibleArea.minY * dpr)));
          const room = Math.min(DETAIL_BUDGET, s.rasterBudget) - s.retainedBytes();
          if (room < visiblePixels * 12) tiles.garbageCollect(Math.max(0, tiles.bytes + room - visiblePixels * 12));
          let available = Math.min(4000000, Math.max(0, Math.floor((Math.min(DETAIL_BUDGET, s.rasterBudget) - s.retainedBytes()) / 12)),
          Math.floor(tiles.capacityBytes((tile) => tile.job.slot === slot) / 4));
          const freeCachePixels = Math.max(0, Math.floor((tiles.maxBytes - tiles.bytes) / 4));
          if (tiles.size < tiles.maxCount && freeCachePixels >= visiblePixels) available = Math.min(available, freeCachePixels);
          const area = officeDetailArea(visibleArea, areaViewport, Math.min(available, requested), dpr);
          const x = Math.floor(area.minX * dpr) / dpr,y = Math.floor(area.minY * dpr) / dpr;
          const width = Math.min(pageWidth, Math.ceil((area.minX + area.width) * dpr) / dpr) - x;
          const height = Math.min(pageHeight, Math.ceil((area.minY + area.height) * dpr) / dpr) - y;
          const priority = (right - left) * Math.max(0, Math.min(bottom, vp.bottom - (options.bottom || 0)) -
          Math.max(top, vp.top + (options.top || 0)));
          const job = { unit, slot, owner: viewer._doc, scroller: s, revision: viewer._renderEpoch, rasterEpoch: s.renderEpoch,
            generation, theme: theme(), scale: viewer.getScale(), pageWidth, pageHeight, x, y, width, height, dpr,
            resourceDpr, minimumDpr: resourceDpr * 1.01, baseCanvas: slot.canvas,
            baseWidth: slot.canvas.width, baseHeight: slot.canvas.height, priority,
            officeReadingPalette: viewer._opts?.officeReadingPalette,
            imageResources: viewer._opts?.imageResources, defaultTextColor: viewer._opts?.defaultTextColor,
            currentDate: viewer._currentDate, showTrackedChanges: !!viewer._showTrackedChanges };
          const rejected = unsupported.get(unit);
          if (rejected && rejected.owner === job.owner && rejected.revision === job.revision && Object.is(rejected.theme, job.theme) &&
          rejected.imageResources === job.imageResources && rejected.defaultTextColor === job.defaultTextColor &&
          rejected.currentDate === job.currentDate &&
          rejected.showTrackedChanges === job.showTrackedChanges) continue;
          if (valid(job)) {
            let capable = false;
            try {capable = !options.canRenderRegion || options.canRenderRegion(Object.freeze({ ...job }));}
            catch (error) {reportFailure(error, job);}
            if (capable) requests.push(job);else
            {
              for (const tile of [...tiles.values()]) if (tile.job.slot === slot) tiles.removeTile(tile.canvas);
              controller.forget(slot);
            }
          }
        }
        requests.sort((a, b) => b.priority - a.priority);
        return controller.requestAll(requests);
      }

    }
    function invalidate() {generation++;unsupported.clear();controller.invalidate();tiles.clear();}
    const api = { request,
      beginInteraction() {if (closed || interaction) return;interaction = true;controller.invalidate();retire();},
      endInteraction() {if (closed) return;interaction = false;},
      notify(reason = 'change') {
        if (closed) return false;
        if (['theme', 'reopen', 'document', 'invalidate'].includes(reason)) invalidate();
        const result = request();controller.notifyIdle();return result;
      },
      notifyIdle() {if (!closed && !paused) {request();controller.notifyIdle();}}, invalidate,
      pause() {if (closed) return;paused = true;interaction = false;controller.pause();invalidate();},
      resume() {if (closed) return false;paused = false;controller.resume();return request();},
      state() {const s = viewer._scroller;return { installed, failed, lastJob, closed, paused, generation,
          pending: !!controller.pending, wanted: controller.wanted,
          targets: [...controller.targets.values()].map((e) => ({ unit: e.request.unit, status: e.status })),
          retainedBytes: s.retainedBytes(), reservationBytes: s.rasterReservation, budget: Math.min(48 * 1024 * 1024, s.rasterBudget), tileCount: tiles.size };},
      close() {if (closed) return;closed = true;controller.close();invalidate();}
    };
    api.dispose = api.close;api.currentState = api.state;return api;
  }

  function attach(viewer, options = {}) {
    if (!regionSupport(viewer)) return null;
    const host = viewer._scrollHost,scroller = viewer._scroller;
    let closed = false,paused = false,frame = 0,idlePending = false,unsubscribe,observer,resize;
    const continuations = new Set();
    const safe = (callback) => {try {return callback();} catch {return false;}};
    const schedule = (callback) => {
      const id = requestAnimationFrame(() => {continuations.delete(id);if (!closed && !paused) safe(callback);});
      continuations.add(id);
    };
    const adapter = attachAdapter(viewer, DetailController, { ...options, schedule });
    function reconcile() {
      frame = 0;
      if (closed || paused) return;
      if (viewer._scroller !== scroller || !regionSupport(viewer)) {api.dispose();return;}
      const wake = idlePending;idlePending = false;
      safe(() => wake ? adapter.notifyIdle() : adapter.request());
    }
    function queue(idle = false) {
      if (closed || paused) return;
      idlePending = idlePending || idle;
      if (!frame) frame = requestAnimationFrame(reconcile);
    }
    const scroll = () => queue();
    function cancelFrames() {cancelAnimationFrame(frame);frame = 0;for (const id of continuations) cancelAnimationFrame(id);continuations.clear();}
    const api = {
      request: () => queue(), notifyIdle: () => queue(true),
      notify(reason = 'change') {
        if (closed) return;
        if (['theme', 'reopen', 'document', 'invalidate'].includes(reason)) safe(() => adapter.invalidate());
        queue();
      },
      beginInteraction() {if (!closed) safe(() => adapter.beginInteraction());},
      endInteraction() {if (!closed) {safe(() => adapter.endInteraction());queue();}},
      pause() {if (closed || paused) return;paused = true;idlePending = false;cancelFrames();safe(() => adapter.pause());},
      resume() {if (closed) return;paused = false;safe(() => adapter.resume());queue();},
      invalidate() {if (!closed) safe(() => adapter.invalidate());},
      state: () => safe(() => adapter.state()),
      dispose() {
        if (closed) return;closed = true;cancelFrames();
        host.removeEventListener('scroll', scroll);window.removeEventListener('resize', scroll);
        observer?.disconnect();resize?.disconnect();safe(() => unsubscribe?.());
        safe(() => adapter.dispose());
      }
    };
    try {
      unsubscribe = scroller.observeDetailIdle(() => queue(true));
      if (typeof unsubscribe !== 'function') throw Error('invalid detail idle subscription');
      host.addEventListener('scroll', scroll, { passive: true });window.addEventListener('resize', scroll);
      observer = new MutationObserver((records) => {
        const relevant = records.some((record) => {
          const target = record.target;
          if (record.type === 'attributes') return !target.hasAttribute('data-office-detail');
          return [...record.addedNodes, ...record.removedNodes].some((node) => node.nodeType === 1 &&
          !node.hasAttribute('data-office-detail') && (node.hasAttribute('data-office-unit') ||
          node.tagName === 'CANVAS' || node.querySelector?.('[data-office-unit]')));
        });
        if (relevant) queue();
      });
      observer.observe(host, { subtree: true, childList: true, attributes: true,
        attributeFilter: ['data-office-unit', 'data-office-valid-unit', 'data-office-painted-unit', 'data-office-painted-scale', 'width', 'height'] });
      resize = new ResizeObserver(scroll);resize.observe(host);
      queue();
      return Object.freeze(api);
    } catch {api.dispose();return null;}
  }
  window.OfficeDetail = Object.freeze({ attach });
})();
