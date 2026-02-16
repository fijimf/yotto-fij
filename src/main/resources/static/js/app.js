document.addEventListener("DOMContentLoaded", function () {
    var toggle = document.querySelector(".nav__toggle");
    var links = document.querySelector(".nav__links");
    if (toggle && links) {
        toggle.addEventListener("click", function () {
            links.classList.toggle("nav__links--open");
        });
    }
});
