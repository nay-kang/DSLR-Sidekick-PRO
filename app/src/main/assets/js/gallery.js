// Gallery JavaScript - DSLR Photo Gallery with PhotoSwipe (Memory Optimized)

let photos = [];
let lightbox = null;
let autoRefreshTimer = null;
let currentETag = null; // Store ETag for conditional requests
let eventSource = null; // SSE connection for real-time updates

// Initialize Server-Sent Events for real-time photo updates
function initSSE() {
    if (eventSource) {
        console.log('SSE already connected, skipping...');
        return;
    }
    
    console.log('Initializing SSE connection to /api/events...');
    eventSource = new EventSource('/api/events');
    
    eventSource.onopen = function() {
        console.log('✅ SSE connection established');
    };
    
    eventSource.onmessage = function(event) {
        try {
            const data = JSON.parse(event.data);
            
            if (data.type === 'new_photo') {
                console.log('📸 New photo detected:', data.name);
                // Immediately reload photos when notified
                loadPhotos();
            } else if (data.type === 'connected') {
                console.log('🔗', data.message);
            }
        } catch (e) {
            console.error('❌ Error parsing SSE message:', e);
        }
    };
    
    eventSource.onerror = function(error) {
        console.error('❌ SSE error:', error);
        console.log('SSE will automatically reconnect...');
        // EventSource will automatically reconnect
    };
}

// Load photos from API with ETag support
async function loadPhotos() {
    try {
        const headers = {};
        if (currentETag) {
            headers['If-None-Match'] = currentETag;
        }
        
        const response = await fetch('/api/photos', { headers });
        
        // Check if content changed (304 Not Modified)
        if (response.status === 304) {
            console.log('Photos unchanged (304 Not Modified)');
            return; // No changes, skip update
        }
        
        // Get new ETag
        const newETag = response.headers.get('ETag');
        const newPhotos = await response.json();
        
        // Update ETag
        if (newETag) {
            currentETag = newETag;
        }
        
        // Check if photos actually changed
        const hasChanged = JSON.stringify(newPhotos) !== JSON.stringify(photos);
        
        if (hasChanged) {
            console.log('Photos updated:', photos.length, '->', newPhotos.length);
            photos = newPhotos;
            renderGallery();
            
            // Initialize PhotoSwipe only if library is loaded
            if (window.PhotoSwipeLightbox) {
                initPhotoSwipe();
            } else {
                console.warn('⚠️ PhotoSwipe not available, will initialize later');
                // Wait for PhotoSwipe to load
                setTimeout(() => {
                    if (window.PhotoSwipeLightbox) {
                        initPhotoSwipe();
                    }
                }, 1000);
            }
        }
    } catch (error) {
        console.error('Error loading photos:', error);
        document.getElementById('gallery').innerHTML = 
            '<div class="no-photos">Error loading photos. Please refresh.</div>';
    }
}

// Render gallery grid
function renderGallery() {
    const gallery = document.getElementById('gallery');
    
    if (photos.length === 0) {
        gallery.innerHTML = '<div class="no-photos">No photos available yet.<br>Connect your camera to start syncing!</div>';
        return;
    }
    
    // Create photo cards with PhotoSwipe data attributes
    // Use reasonable default dimensions - will be corrected when image opens
    gallery.innerHTML = photos.map((photo, index) => {
        const photoId = encodeURIComponent(photo.name);
        return '<a href="/api/photo/' + photoId + '" ' +
               'data-pswp-width="4000" ' +
               'data-pswp-height="3000" ' +
               'data-index="' + index + '" ' +
               'target="_blank">' +
               '<div class="photo-card">' +
                   '<img src="/api/thumb/' + photoId + '" alt="Photo ' + (index + 1) + '" loading="lazy">' +
               '</div>' +
               '</a>';
    }).join('');
    
    // Add load event listeners for fade-in effect only
    setTimeout(() => {
        const images = gallery.querySelectorAll('.photo-card img');
        images.forEach(img => {
            if (img.complete) {
                img.classList.add('loaded');
            } else {
                img.addEventListener('load', function() {
                    this.classList.add('loaded');
                });
            }
        });
    }, 50);
}

// Initialize PhotoSwipe with memory optimization
function initPhotoSwipe() {
    // Check if PhotoSwipe is available
    if (!window.PhotoSwipeLightbox) {
        console.warn('⚠️ PhotoSwipe not loaded yet, skipping initialization');
        return;
    }
    
    if (lightbox) {
        lightbox.destroy();
    }
    
    lightbox = new window.PhotoSwipeLightbox({
        gallery: '#gallery',
        children: 'a',
        pswpModule: () => import('https://cdn.jsdelivr.net/npm/photoswipe@5.4.4/dist/photoswipe.esm.min.js'),
        
        // Memory optimization settings
        preload: [1, 2], // Load 1 image before, 2 after (reduces memory usage)
        maxZoomLevel: 5,
        minZoomLevel: 0.5,
        
        // Performance settings
        showHideAnimationType: 'fade', // Faster than zoom animation
        closeOnVerticalDrag: true,
        closeWithTitle: false,
        
        // Darker background for better viewing experience
        bgOpacity: 1, // Fully opaque black background
        
        // Fix thumbnail flash issue - don't use thumbnail as placeholder
        showHideOpacity: true,
        
        // Dynamic image dimensions - fetch when opening
        getContent: function(content) {
            return new Promise((resolve, reject) => {
                const img = new Image();
                img.onload = function() {
                    content.width = this.naturalWidth;
                    content.height = this.naturalHeight;
                    content.element = this;
                    resolve(content);
                };
                img.onerror = reject;
                img.src = content.src;
            });
        }
    });
    
    // Show image after it loads
    lightbox.on('contentLoadImage', function(event) {
        const { content } = event;
        if (content && content.element) {
            // Make sure the image is visible
            setTimeout(() => {
                content.element.style.opacity = '1';
            }, 50);
        }
    });
    
    // Also handle initial open
    lightbox.on('open', function() {
        setTimeout(() => {
            const currentSlide = document.querySelector('.pswp__item.pswp__item--current img');
            if (currentSlide) {
                currentSlide.style.opacity = '1';
            }
        }, 100);
    });
    
    lightbox.init();
}

// Handle orientation change
let orientationTimeout = null;
window.addEventListener('orientationchange', function() {
    // Debounce orientation change
    clearTimeout(orientationTimeout);
    orientationTimeout = setTimeout(() => {
        // Force layout recalculation
        document.body.style.width = '100%';
        document.body.offsetHeight; // Trigger reflow
        document.body.style.width = '';
        
        // If PhotoSwipe is open, update its layout
        if (lightbox && lightbox.pswp && lightbox.pswp.isOpen) {
            lightbox.pswp.updateSize(true);
        }
    }, 300);
});

// Also handle resize for desktop window resizing
let resizeTimeout = null;
window.addEventListener('resize', function() {
    clearTimeout(resizeTimeout);
    resizeTimeout = setTimeout(() => {
        if (lightbox && lightbox.pswp && lightbox.pswp.isOpen) {
            lightbox.pswp.updateSize(true);
        }
    }, 300);
});

// Auto-refresh every 30 seconds to check for new photos (only when not viewing)
function startAutoRefresh() {
    // Clear existing timer
    if (autoRefreshTimer) {
        clearInterval(autoRefreshTimer);
    }
    
    autoRefreshTimer = setInterval(() => {
        // Only refresh if PhotoSwipe is not open
        if (!lightbox || !lightbox.pswp || !lightbox.pswp.isOpen) {
            loadPhotos();
        }
    }, 30000);
}

// Initial load
loadPhotos();
initSSE(); // Start SSE connection for real-time updates
startAutoRefresh(); // Fallback polling in case SSE fails

// Wait for PhotoSwipe to be available, then initialize
function waitForPhotoSwipe() {
    if (window.PhotoSwipeLightbox) {
        console.log('✅ PhotoSwipe loaded, initializing...');
        initPhotoSwipe();
    } else {
        console.log('⏳ Waiting for PhotoSwipe to load...');
        setTimeout(waitForPhotoSwipe, 100);
    }
}

waitForPhotoSwipe();
