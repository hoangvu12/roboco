# Transcript web links

Click an HTTP(S) link in an agent message to open it in your default browser. Roboco has no in-app browser on the desktop ([ADR 0012](adr/0012-no-web-client-no-embedded-browser.md)), so there is no "Open in Roboco" choice and no preference to set.

Right-click a link for **Open link** (the default browser) or **Copy link address**. Tab and Shift+Tab move through links and other controls when an input or completion menu does not consume the key. Enter or Space activates a focused link. Shift+F10 opens its action menu; arrows or Tab move through the actions, and Escape dismisses the menu.

Hover or keyboard focus shows the full destination, including when the Markdown label says something different. The compact tooltip fits the destination text up to 360 px, wraps long destinations, and scrolls within the window. Opening the context menu hides the tooltip and cancels pending hover disclosure until the menu closes. Inspecting a link makes no network requests. Scrolling the transcript, changing conversations, or removing a link dismisses its disclosure.

Long web links are shortened with an ellipsis according to the actual available text width, including inside table cells. Navigation, destination disclosure, and Copy link address retain the complete URL. Text selection uses the original label: selecting an entire shortened URL copies the full URL, and selecting its visible prefix copies that prefix. Selecting across the ellipsis includes the omitted text. Labels, styles, and selection positions update when the window width or streaming content changes.

Only explicit HTTP(S) web addresses without embedded credentials, control characters, whitespace, backslashes, or malformed percent escapes can navigate. Rejected links never fall through to the OS opener. Workspace/file links keep their file-opening behavior and open in the editor. Markdown file previews open web links the same way, with the same Open/Copy actions, and keep their local-file, anchor, and mail-link handling; their labels are not visually truncated.
