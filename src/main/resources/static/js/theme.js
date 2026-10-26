/*
 * Light and dark themes for the console.
 *
 * Three choices, cycled by any [data-theme-toggle] button: System (follow the device), Light and
 * Dark. The choice is remembered in this browser only. The stylesheet keys on data-theme on
 * <html>, which is always "light" or "dark": System is resolved here, so the CSS needs a single
 * dark block.
 *
 * Loaded synchronously from <head>, from this origin (the content security policy allows no
 * inline script), so the theme is applied before the first paint and the page never flashes
 * light on a dark device. Without JavaScript the page simply stays light, and the switch stays
 * hidden rather than sitting there doing nothing.
 */
(function () {
    'use strict';

    var KEY = 'pivot-theme';
    var CHOICES = ['system', 'light', 'dark'];
    var LABELS = { system: 'System', light: 'Light', dark: 'Dark' };
    var ICONS = { system: '◐', light: '☀', dark: '☾' };
    var root = document.documentElement;
    var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

    // Storage can be unavailable (private windows, blocked site data); the theme still works,
    // it just is not remembered.
    function storedChoice() {
        try {
            var value = window.localStorage.getItem(KEY);
            return CHOICES.indexOf(value) >= 0 ? value : 'system';
        } catch (e) {
            return 'system';
        }
    }

    function storeChoice(choice) {
        try {
            window.localStorage.setItem(KEY, choice);
        } catch (e) {
            /* not remembered; nothing else to do */
        }
    }

    function apply(choice) {
        var dark = choice === 'dark' || (choice === 'system' && media !== null && media.matches);
        root.setAttribute('data-theme', dark ? 'dark' : 'light');
    }

    function render(button, choice) {
        var next = CHOICES[(CHOICES.indexOf(choice) + 1) % CHOICES.length];
        button.querySelector('.theme-toggle-icon').textContent = ICONS[choice];
        button.querySelector('.theme-toggle-label').textContent = 'Theme: ' + LABELS[choice];
        button.setAttribute('aria-label', 'Theme: ' + LABELS[choice] + '. Switch to ' + LABELS[next] + '.');
        button.title = 'Switch to ' + LABELS[next] + ' theme';
    }

    apply(storedChoice());

    // Follow the device while the choice is System: an operator whose laptop switches to dark
    // mode at dusk gets the dark console without reloading.
    if (media !== null && media.addEventListener) {
        media.addEventListener('change', function () {
            if (storedChoice() === 'system') {
                apply('system');
            }
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        var buttons = document.querySelectorAll('[data-theme-toggle]');
        Array.prototype.forEach.call(buttons, function (button) {
            render(button, storedChoice());
            button.hidden = false;
            button.addEventListener('click', function () {
                var current = storedChoice();
                var next = CHOICES[(CHOICES.indexOf(current) + 1) % CHOICES.length];
                storeChoice(next);
                apply(next);
                Array.prototype.forEach.call(buttons, function (each) { render(each, next); });
            });
        });
    });
}());
