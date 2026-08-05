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
 * Irreversible SHA-256 hash using Web Crypto API.
 * Used to hash password before sending to backend.
 */
async function sha256(message) {
    var msgBuffer = new TextEncoder().encode(message);
    var hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer);
    var hashArray = Array.from(new Uint8Array(hashBuffer));
    var hashHex = hashArray.map(function(b) { return b.toString(16).padStart(2, '0'); }).join('');
    return hashHex;
}
