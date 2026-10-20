package dev.tylercash.event.tarot;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface TarotChannelRepository extends JpaRepository<TarotChannel, TarotChannel.Key> {

    List<TarotChannel> findByGuildId(long guildId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TarotChannel c WHERE c.guildId = :guildId")
    void deleteByGuildId(long guildId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TarotChannel c WHERE c.channelId = :channelId")
    int deleteByChannelId(long channelId);
}
