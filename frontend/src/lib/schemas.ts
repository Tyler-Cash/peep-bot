import { z } from "zod";

export const UserInfoSchema = z.object({
  username: z.string(),
  displayName: z.string(),
  discordId: z.string(),
  adminGuildIds: z.array(z.string()),
  avatarUrl: z.string().nullable().optional(),
});
export type UserInfo = z.infer<typeof UserInfoSchema>;

export const GuildSchema = z.object({
  id: z.string(),
  name: z.string(),
  initials: z.string(),
  iconUrl: z.string().nullable().optional(),
  color: z.string(),
  channel: z.string(),
  members: z.number(),
  active: z.boolean().optional(),
  primaryLocationLat: z.number().nullable().optional(),
  primaryLocationLng: z.number().nullable().optional(),
});
export type Guild = z.infer<typeof GuildSchema>;
export const GuildListSchema = z.array(GuildSchema);

export const GuildSettingsSchema = z.object({
  primaryLocationPlaceId: z.string().nullable(),
  primaryLocationName: z.string().nullable(),
  primaryLocationLat: z.number().nullable(),
  primaryLocationLng: z.number().nullable(),
  eventsRole: z.string(),
  adminRole: z.string(),
  separatorChannel: z.string().nullable(),
  emojiAccepted: z.string(),
  emojiDeclined: z.string(),
  emojiMaybe: z.string(),
});
export type GuildSettingsDto = z.infer<typeof GuildSettingsSchema>;
