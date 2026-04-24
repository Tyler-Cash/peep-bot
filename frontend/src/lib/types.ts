export type Category = "movie" | "trivia" | "comedy" | "food" | "outdoor" | "game";

export type Attendee = {
  snowflake: string | null;
  name: string;
  instant: string; // ISO
  ownerSnowflake?: string | null;
  avatarUrl?: string | null;
  hue?: string; // fallback accent colour when no avatar
};

export type EventDto = {
  id: number;
  name: string;
  description: string;
  location: string;
  city?: string;
  capacity: number;
  cost?: number | null;
  dateTime: string; // ISO
  host: string;
  hostAvatarUrl?: string | null;
  category: Category;
  state?: "ACTIVE" | "CANCELLED" | "ARCHIVED";
  notifyOnCreate?: boolean;
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
};

export type RsvpStatus = "going" | "maybe" | "declined";

export type RewindStats = {
  year: number;
  eventsHosted: number;
  totalRsvps: number;
  topMoment?: { eventId: number; name: string; category: Category } | null;
  mostActiveMember?: { name: string; count: number; avatarUrl?: string | null; hue?: string } | null;
  newMembers: number;
  attendanceStreak: Array<{ name: string; count: number; avatarUrl?: string | null; hue?: string }>;
  upcomingPreview: EventDto[];
};

export type BackendError = { status: number; message: string };
