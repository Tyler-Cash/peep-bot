export type Category =
  | "movie"
  | "trivia"
  | "comedy"
  | "food"
  | "outdoor"
  | "game";

export type Attendee = {
  snowflake: string | null;
  name: string;
  username?: string | null;
  instant: string; // ISO
  ownerSnowflake?: string | null;
  avatarUrl?: string | null;
  hue?: string; // fallback accent colour when no avatar
};

export type EventDto = {
  id: string; // UUID in backend
  name: string;
  description: string;
  location: string;
  capacity: number;
  cost?: number | null;
  dateTime: string; // ISO
  host: string;
  hostUsername?: string | null;
  hostAvatarUrl?: string | null;
  category: Category;
  state?: "ACTIVE" | "CANCELLED" | "ARCHIVED";
  notifyOnCreate?: boolean;
  channelId?: string;
  messageId?: string;
};

export type EventDetailDto = EventDto & {
  accepted: Attendee[];
  maybe: Attendee[];
  declined: Attendee[];
};

export type EventUpdateDto = {
  id: number;
  name?: string;
  description?: string;
  capacity?: number;
  dateTime?: string;
  accepted?: string[]; // snowflake set
};

export type UserInfo = {
  username: string;
  displayName: string;
  discordId: string;
  admin: boolean;
  avatarUrl?: string | null;
};

export type Guild = {
  id: string;
  name: string;
  initials: string;
  iconUrl?: string | null;
  color: string; // hex
  channel: string; // no '#'
  members: number;
  active?: boolean;
  primaryLocationLat?: number | null;
  primaryLocationLng?: number | null;
};

export type GuildSettingsDto = {
  primaryLocationPlaceId: string | null;
  primaryLocationName: string | null;
  primaryLocationLat: number | null;
  primaryLocationLng: number | null;
};


export type RsvpStatus = "going" | "maybe" | "declined";

export type RewindStats = {
  year: number;
  eventsHosted: number;
  totalRsvps: number;
  topMoment?: { eventId: string; name: string; category: Category } | null;
  mostActiveMember?: {
    name: string;
    count: number;
    avatarUrl?: string | null;
    hue?: string;
  } | null;
  newMembers: number;
  attendanceStreak: Array<{
    name: string;
    count: number;
    avatarUrl?: string | null;
    hue?: string;
  }>;
  upcomingPreview: EventDto[];
};

export type BackendError = { status: number; message: string };
