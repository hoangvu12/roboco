import { ChatList } from "./chat-list";
import { SpaceFilter } from "./space-filter";
import { NewChatButton } from "./new-chat-button";
import { ArchivedSection } from "./archived-section";
import { SidebarNotice } from "./sidebar-notice";

/**
 * The sidebar below its header: space switcher + new-chat affordance, the
 * chat list, the archived shelf, and the mutation notice strip. AppShell
 * keys this by the active engine so menus and dialogs reset on a switch.
 */
export function SidebarBody() {
  return (
    <>
      <div className="sidebar-tools">
        <SpaceFilter />
        <NewChatButton />
      </div>
      <nav className="sidebar-list" aria-label="Chats">
        <ChatList />
      </nav>
      <ArchivedSection />
      <SidebarNotice />
    </>
  );
}
