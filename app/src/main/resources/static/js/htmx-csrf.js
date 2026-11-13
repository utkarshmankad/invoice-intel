// Adds the CSRF token to every HTMX request (architecture.md §11): the UI's form login keeps
// CSRF on, and HTMX posts don't carry the hidden `_csrf` field of a Thymeleaf form.
document.addEventListener("htmx:configRequest", (event) => {
  const header = document.querySelector('meta[name="csrf-header"]');
  const token = document.querySelector('meta[name="csrf-token"]');
  if (header && token) {
    event.detail.headers[header.content] = token.content;
  }
});
