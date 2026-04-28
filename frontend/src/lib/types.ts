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
  id: number;
  name: string;
  description: string;
  location: string;
  locationPlaceId?: string | null;
  capacity: number;
  cost?: number | null;
  dateTime: string; // ISO
  host: string;
  hostUsername?: string | null;
  hostAvatarUrl?: string | null;
  category?: string | null;
  state?: string;
  displayState?: "creating" | "planned" | "archived" | "cancelled" | "deleted" | string;
  notifyOnCreate?: boolean;
  channelId?: string;
  messageId?: string;
};

export type EventDetailDto = EventDto & {
  accepted: Attendee[];
  maybe: Attendee[];
  declined: Attendee[];
  hasPrivateChannel?: boolean;
  completed?: boolean;
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

// Matches backend RewindStatsDto
export type AttendeeStatDto = {
  displayName: string;
  eventCount: number;
  avatarUrl: string | null;
};

export type EventCategoryDto = {
  name: string;
  eventCount: number;
  totalAttendees: number;
};

export type EventSummaryDto = {
  id: string;
  name: string;
  dateTime: string;
};

export type SocialGraphDto = {
  nodes: GraphNodeDto[];
  edges: GraphEdgeDto[];
};

export type GraphNodeDto = {
  snowflake: string;
  displayName: string;
  avatarUrl: string | null;
  eventCount: number;
};

export type GraphEdgeDto = {
  user1Snowflake: string;
  user2Snowflake: string;
  sharedEvents: number;
};

export type RewindStats = {
  year: number;
  totalEvents: number;
  totalUniqueAttendees: number;
  totalRsvps: number;
  averageGroupSize: number;
  topCategories: EventCategoryDto[];
  topAttendees: AttendeeStatDto[];
  topOrganizers: AttendeeStatDto[];
  socialGraph: SocialGraphDto | null;
  eventsByMonth: Record<string, number>;
  eventsByDayOfWeek: Record<string, number>;
  firstEvent: EventSummaryDto | null;
  lastEvent: EventSummaryDto | null;
  totalPlusOneGuests: number;
  embeddingsAvailable: boolean;
};

export type BackendError = { status: number; message: string };

export type GalleryAlbumDto = {
  eventId: string;
  eventName: string;
  eventDateTime: string;
  albumId: string;
  thumbnailUrl: string;
  assetCount: number;
};
