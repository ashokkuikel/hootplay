// Injected into youtube.com before any page script runs.
(function () {
  'use strict';
  if (window.__hootplay) return;
  window.__hootplay = true;

  // ---- Ad removal -------------------------------------------------------
  // The TV app builds everything from JSON responses. Ads arrive as fields
  // in the player response and as ad slots mixed into browse/search lists,
  // so they are dropped from the parsed data before the app sees them.

  var AD_FIELDS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];
  var AD_ITEMS = ['adSlotRenderer', 'tvMastheadRenderer', 'promotedSparklesWebRenderer'];
  var MAX_DEPTH = 60;

  function isAdItem(item) {
    if (!item || typeof item !== 'object') return false;
    for (var i = 0; i < AD_ITEMS.length; i++) {
      if (item[AD_ITEMS[i]]) return true;
    }
    return false;
  }

  function prune(node, depth) {
    if (!node || typeof node !== 'object' || depth > MAX_DEPTH) return;
    if (Array.isArray(node)) {
      for (var i = node.length - 1; i >= 0; i--) {
        if (isAdItem(node[i])) {
          node.splice(i, 1);
          removed++;
        } else {
          prune(node[i], depth + 1);
        }
      }
      return;
    }
    for (var f = 0; f < AD_FIELDS.length; f++) {
      if (AD_FIELDS[f] in node) {
        delete node[AD_FIELDS[f]];
        removed++;
      }
    }
    for (var key in node) {
      var child = node[key];
      if (child && typeof child === 'object') prune(child, depth + 1);
    }
  }

  function looksLikeYouTubeData(obj) {
    return obj && typeof obj === 'object' && (
      obj.responseContext || obj.playerResponse || obj.adPlacements ||
      obj.contents || obj.continuationContents || obj.onResponseReceivedActions ||
      obj.streamingData || Array.isArray(obj));
  }

  var removed = 0;

  // Thumbnail previews on browse screens play in a small box. Their format
  // list is trimmed to 480p and below so they start quickly; a video that is
  // actually opened (#/watch) keeps every format the TV can play.
  var PREVIEW_MAX_HEIGHT = 480;

  function limitPreview(obj) {
    if (/\/watch/.test(location.hash)) return;
    var response = obj.streamingData ? obj : obj.playerResponse;
    var data = response && response.streamingData;
    if (!data || !Array.isArray(data.adaptiveFormats)) return;
    var kept = data.adaptiveFormats.filter(function (f) {
      return !f.height || f.height <= PREVIEW_MAX_HEIGHT;
    });
    var hasVideo = kept.some(function (f) { return f.height; });
    if (hasVideo && kept.length < data.adaptiveFormats.length) {
      data.adaptiveFormats = kept;
      response.__hootplayPreview = true;
    }
  }

  function clean(obj) {
    try {
      if (obj && typeof obj === 'object') limitPreview(obj);
      if (looksLikeYouTubeData(obj)) {
        var before = removed;
        prune(obj, 0);
        if (removed > before) {
          console.log('[Hootplay] removed ' + (removed - before) + ' ad entries (' + removed + ' total)');
        }
      }
    } catch (e) {
      console.log('[Hootplay] prune failed: ' + e);
    }
    return obj;
  }

  // Troubleshooting switch: localStorage['hootplay.off'] = '1' leaves responses untouched.
  var disabled = false;
  try { disabled = localStorage.getItem('hootplay.off') === '1'; } catch (e) {}

  var nativeParse = JSON.parse;
  if (!disabled) {
    JSON.parse = function () {
      return clean(nativeParse.apply(this, arguments));
    };
  }

  if (!disabled && window.Response && Response.prototype.json) {
    var nativeJson = Response.prototype.json;
    Response.prototype.json = function () {
      return nativeJson.apply(this, arguments).then(clean);
    };
  }

  // Fallback: if a video ad still starts, jump to its end.
  setInterval(function () {
    var player = document.querySelector('.html5-video-player');
    if (!player || !player.classList.contains('ad-showing')) return;
    var video = document.querySelector('video');
    if (video && isFinite(video.duration) && video.duration > 0) {
      video.currentTime = video.duration;
    }
  }, 500);

  // ---- Video quality ----------------------------------------------------
  // YouTube's TV player checks whether the device answers codec questions
  // truthfully by asking about impossible values (width=99999, framerate=9999,
  // made-up option names). The WebView ignores those parameters and says yes
  // to everything, so the player stops trusting it and limits playback to
  // 1080p at 30 fps. Rejecting what is out of range restores 1440p/4K/60 fps.

  // The limits are not fixed: the Android side reads the TV panel's largest
  // display mode and what its video decoders accept, and passes them in as
  // window.__hootplayCaps = { width, height, fps, codecs: { vp9: {width, height, fps}, ... } }.
  var caps = window.__hootplayCaps || {};
  var MAX_WIDTH = caps.width || 1920, MAX_HEIGHT = caps.height || 1080, MAX_FPS = caps.fps || 60;
  var MAX_BITRATE = 80000000;

  function codecFamily(type) {
    var m = /codecs="?\s*([a-z0-9]+)/i.exec(type);
    var c = m ? m[1].toLowerCase() : '';
    if (c.indexOf('vp09') === 0 || c === 'vp9') return 'vp9';
    if (c.indexOf('av01') === 0) return 'av1';
    if (c.indexOf('avc') === 0) return 'avc';
    if (c.indexOf('hvc') === 0 || c.indexOf('hev') === 0) return 'hevc';
    return '';
  }

  function withinLimits(type) {
    var params = {}, m, re = /;\s*([\w-]+)=("?)([^;"]*)\2/g;
    while ((m = re.exec(type))) params[m[1].toLowerCase()] = m[3];
    var codec = (caps.codecs || {})[codecFamily(type)] || {};
    var numeric = {
      width: Math.min(MAX_WIDTH, codec.width || MAX_WIDTH),
      height: Math.min(MAX_HEIGHT, codec.height || MAX_HEIGHT),
      framerate: Math.min(MAX_FPS, codec.fps || MAX_FPS),
      bitrate: MAX_BITRATE,
      channels: 6
    };
    for (var name in numeric) {
      if (name in params) {
        var value = Number(params[name]);
        if (!(value > 0) || value > numeric[name]) return false;
      }
    }
    if (params.eotf && !/^(bt709|smpte2084|arib-std-b67)$/.test(params.eotf)) return false;
    if (params.cryptoblockformat && !/^(subsample|cenc|cbcs)$/.test(params.cryptoblockformat)) return false;
    if (params['decode-to-texture'] && params['decode-to-texture'] !== 'false') return false;
    if (params.experimental && params.experimental !== 'allowed') return false;
    return true;
  }

  if (!disabled && window.MediaSource && MediaSource.isTypeSupported) {
    var nativeIsTypeSupported = MediaSource.isTypeSupported.bind(MediaSource);
    MediaSource.isTypeSupported = function (type) {
      if (!nativeIsTypeSupported(type)) return false;
      try { return withinLimits(String(type)); } catch (e) { return true; }
    };
  }

  // ---- Host integration -------------------------------------------------

  // Remote buttons forwarded from the Android side (Back, media keys).
  window.__hootplayKey = function (type, code) {
    var ev = new KeyboardEvent(type, { bubbles: true, cancelable: true });
    Object.defineProperty(ev, 'keyCode', { get: function () { return code; } });
    Object.defineProperty(ev, 'which', { get: function () { return code; } });
    (document.activeElement || document.body || document).dispatchEvent(ev);
  };

  // "Exit" in YouTube's own dialog calls window.close().
  window.close = function () {
    if (window.HootplayHost) window.HootplayHost.exit();
  };

  // Keep the TV awake only while a video the viewer opened is playing. Menus,
  // home-screen previews and paused or finished videos let the TV's own
  // screensaver and sleep timer take over.
  var keepingAwake = false;
  setInterval(function () {
    var playing = false;
    if (/\/watch/.test(location.hash)) {
      var videos = document.querySelectorAll('video');
      for (var i = 0; i < videos.length; i++) {
        var v = videos[i];
        if (!v.paused && !v.ended && v.readyState > 2) playing = true;
      }
    }
    if (playing !== keepingAwake && window.HootplayHost && window.HootplayHost.setPlaying) {
      keepingAwake = playing;
      window.HootplayHost.setPlaying(playing);
    }
  }, 2000);

  window.addEventListener('load', function () {
    console.log('[Hootplay] loaded ' + location.href + ' viewport ' +
      innerWidth + 'x' + innerHeight + ' dpr ' + devicePixelRatio);
  });
})();
