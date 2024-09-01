package dev.tylercash.event.event.model.converter;

import dev.tylercash.event.event.model.Notification;
import jakarta.persistence.Converter;

@Converter
public class SetOfNotificationsConverter extends SetOfPojoConverter<Notification> {
    public SetOfNotificationsConverter() {
        super(Notification.class);
    }
}