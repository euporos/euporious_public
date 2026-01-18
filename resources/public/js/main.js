(function() {
  const emailMap = {
    "1": [13, 129, 114, 112, 117, 123, 118, 112, 110, 121, 77, 124, 121, 118, 131, 114, 127, 122, 124, 129, 135, 59, 112, 124, 122]
  };

  function decodeEmail(id) {
    const data = emailMap[id];
    if (!data) return null;

    const offset = data[0];
    const codes = data.slice(1);

    return codes.map(code => String.fromCharCode(code - offset)).join('');
  }

  function initEmailLinks() {
    document.querySelectorAll('a[data-ml]').forEach(function(link) {
      const emailId = link.getAttribute('data-ml');

      link.addEventListener('click', function(e) {
        e.preventDefault();
        const email = decodeEmail(emailId);
        if (email) {
          window.location.href = 'mailto:' + email;
        }
      });

      // Set cursor to pointer to indicate clickability
      link.style.cursor = 'pointer';
    });
  }

  // Initialize when DOM is ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initEmailLinks);
  } else {
    initEmailLinks();
  }
})();
