// Auto Deploy Tool - Common JS

$(document).ready(function() {
    // Initialize Bootstrap tooltips for all [data-bs-toggle="tooltip"] elements
    var tooltipTriggerList = [].slice.call(document.querySelectorAll('[data-bs-toggle="tooltip"]'));
    tooltipTriggerList.forEach(function(el) {
        new bootstrap.Tooltip(el);
    });

    // Auto-close alerts after 5 seconds
    setTimeout(function() {
        var alerts = document.querySelectorAll('.alert-dismissible');
        alerts.forEach(function(alert) {
            var bsAlert = bootstrap.Alert.getOrCreateInstance(alert);
            bsAlert.close();
        });
    }, 5000);

    // Highlight active sidebar item based on current URL
    var currentPath = window.location.pathname;
    $('.sidebar .list-group-item').each(function() {
        var href = $(this).attr('href');
        if (href && currentPath.startsWith(href)) {
            $(this).addClass('active');
        }
    });

    // Inline validation feedback for required form fields
    $('input[required], select[required], textarea[required]').on('blur change', function() {
        var field = $(this);
        if (this.checkValidity()) {
            field.addClass('is-valid').removeClass('is-invalid');
        } else {
            field.addClass('is-invalid').removeClass('is-valid');
        }
    });
});

/**
 * Show loading overlay with spinner
 */
function showLoading() {
    if (document.getElementById('ad-loading-overlay')) return;
    var overlay = document.createElement('div');
    overlay.id = 'ad-loading-overlay';
    overlay.className = 'ad-loading-overlay';
    overlay.innerHTML = '<div class="ad-spinner"></div>';
    document.body.appendChild(overlay);
}

/**
 * Hide loading overlay
 */
function hideLoading() {
    var overlay = document.getElementById('ad-loading-overlay');
    if (overlay) overlay.remove();
}

/**
 * Show toast notification
 * @param {string} message - The message to display
 * @param {string} type - 'success', 'danger', 'warning', 'info'
 */
function showToast(message, type) {
    type = type || 'info';
    var container = document.querySelector('.ad-toast-container');
    if (!container) {
        container = document.createElement('div');
        container.className = 'ad-toast-container';
        document.body.appendChild(container);
    }

    var iconMap = {
        success: 'bi-check-circle-fill',
        danger: 'bi-x-circle-fill',
        warning: 'bi-exclamation-triangle-fill',
        info: 'bi-info-circle-fill'
    };

    var toastId = 'toast-' + Date.now();
    var html = '<div id="' + toastId + '" class="toast align-items-center text-bg-' + type + ' border-0" role="alert">' +
        '<div class="d-flex">' +
        '<div class="toast-body"><i class="bi ' + (iconMap[type] || iconMap.info) + ' me-2"></i>' + message + '</div>' +
        '<button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button>' +
        '</div></div>';

    container.insertAdjacentHTML('beforeend', html);
    var toastEl = document.getElementById(toastId);
    var toast = new bootstrap.Toast(toastEl, { delay: 4000 });
    toast.show();
    toastEl.addEventListener('hidden.bs.toast', function() { toastEl.remove(); });
}

/**
 * Irreversible SHA-256 hash.
 * Uses Web Crypto API in secure contexts, falls back to pure JS otherwise.
 */
function sha256(message) {
    if (window.crypto && window.crypto.subtle) {
        var msgBuffer = new TextEncoder().encode(message);
        return window.crypto.subtle.digest('SHA-256', msgBuffer).then(function(hashBuffer) {
            var hashArray = Array.from(new Uint8Array(hashBuffer));
            return hashArray.map(function(b) { return b.toString(16).padStart(2, '0'); }).join('');
        });
    }
    return Promise.resolve(sha256Pure(message));
}

function sha256Pure(message) {
    var K = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ];
    function rotr(n, x) { return (x >>> n) | (x << (32 - n)); }
    function ch(x, y, z) { return (x & y) ^ (~x & z); }
    function maj(x, y, z) { return (x & y) ^ (x & z) ^ (y & z); }
    function sigma0(x) { return rotr(2, x) ^ rotr(13, x) ^ rotr(22, x); }
    function sigma1(x) { return rotr(6, x) ^ rotr(11, x) ^ rotr(25, x); }
    function gamma0(x) { return rotr(7, x) ^ rotr(18, x) ^ (x >>> 3); }
    function gamma1(x) { return rotr(17, x) ^ rotr(19, x) ^ (x >>> 10); }

    var bytes = new TextEncoder().encode(message);
    var len = bytes.length;
    var bitLen = len * 8;
    var padLen = 64 - ((len + 9) % 64);
    if (padLen === 64) padLen = 0;
    var totalLen = len + 1 + padLen + 8;
    var padded = new Uint8Array(totalLen);
    padded.set(bytes);
    padded[len] = 0x80;
    var view = new DataView(padded.buffer);
    view.setUint32(totalLen - 4, bitLen, false);

    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var W = new Array(64);

    for (var offset = 0; offset < totalLen; offset += 64) {
        for (var i = 0; i < 16; i++) {
            W[i] = view.getUint32(offset + i * 4, false);
        }
        for (var i = 16; i < 64; i++) {
            W[i] = (gamma1(W[i-2]) + W[i-7] + gamma0(W[i-15]) + W[i-16]) | 0;
        }
        var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
        for (var i = 0; i < 64; i++) {
            var T1 = (h + sigma1(e) + ch(e, f, g) + K[i] + W[i]) | 0;
            var T2 = (sigma0(a) + maj(a, b, c)) | 0;
            h = g; g = f; f = e;
            e = (d + T1) | 0;
            d = c; c = b; b = a;
            a = (T1 + T2) | 0;
        }
        H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
        H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }

    var result = '';
    for (var i = 0; i < 8; i++) {
        result += ('00000000' + (H[i] >>> 0).toString(16)).slice(-8);
    }
    return result;
}
