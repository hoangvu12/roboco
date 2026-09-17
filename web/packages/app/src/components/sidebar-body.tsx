import { ChatList } from "./chat-list";
import { SpaceFilter } from "./space-filter";
import { NewChatListener } from "./new-chat-button";
import { ArchivedSection } from "./archived-section";
import { SidebarNotice } from "./sidebar-notice";
import { AccountRow } from "./account-row";
import { ConnectionPill } from "./connection-pill";
import { UpdateStrip } from "./update-strip";

/**
 * The sidebar's column — the desktop's `render_chat_sidebar`: the space
 * filter header, the global active-chat list, the archived shelf, the notice
 * strip, the connection line, the update strip, and the user menu pinned to
 * the bottom.
 *
 * New-chat creation is NOT here: the desktop's titlebar owns that action in
 * both sidebar states, so the `+` lives in `Titlebar` and this column only
 * lists. AppShell keys this by the active engine so menus and dialogs reset
 * on a switch.
 */
export function SidebarBody() {
  return (
    <>
      <NewChatListener />
      <div className="sidebar-scroll">
        <SpaceFilter />
        <nav className="sidebar-list" aria-label="Chats">
          <ChatList />
        </nav>
        <ArchivedSection />
      </div>
      <SidebarNotice />
      <ConnectionPill />
      <UpdateStrip />
      <AccountRow />
    </>
  );
}
