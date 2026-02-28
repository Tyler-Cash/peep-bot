import React from 'react';
import {Link} from "react-router-dom";
import moment from "moment-timezone";
import './css/events.css';

export default function EventOverview(props) {
    const eventMoment = moment(props.date).tz(moment.tz.guess(true));
    const dayNum = eventMoment.format("D");
    const monthAbbr = eventMoment.format("MMM").toUpperCase();
    const dayName = eventMoment.format("ddd").toUpperCase();
    const timeStr = eventMoment.format("h:mm A");
    const acceptedCount = props.acceptedCount || 0;

    return (
        <div className="event-overview-card">
            <div className="event-date-badge">
                <span className="event-date-badge-month">{monthAbbr}</span>
                <span className="event-date-badge-day">{dayNum}</span>
                <span className="event-date-badge-divider"/>
                <span className="event-date-badge-weekday">{dayName}</span>
                <span className="event-date-badge-time">{timeStr}</span>
            </div>
            <div className="event-overview-content">
                <h5 className="event-overview-name">{props.name}</h5>
                {props.description && (
                    <p className="event-overview-description">{props.description}</p>
                )}
                <div className="event-overview-footer">
                    {acceptedCount > 0 ? (
                        <span className="attendee-count-chip">
                            <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" fill="currentColor"
                                 viewBox="0 0 16 16">
                                <path d="M7 14s-1 0-1-1 1-4 5-4 5 3 5 4-1 1-1 1zm4-6a3 3 0 1 0 0-6 3 3 0 0 0 0 6m-5.784 6A2.24 2.24 0 0 1 5 13c0-1.355.68-2.75 1.936-3.72A6.3 6.3 0 0 0 5 9c-4 0-5 3-5 4s1 1 1 1zM4.5 8a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5"/>
                            </svg>
                            {acceptedCount} going
                        </span>
                    ) : <span/>}
                    <Link to={`/event/${props.id}`} className="btn-edit-card">
                        <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" fill="currentColor"
                             viewBox="0 0 16 16">
                            <path
                                d="M12.146.146a.5.5 0 0 1 .708 0l3 3a.5.5 0 0 1 0 .708l-10 10a.5.5 0 0 1-.168.11l-5 2a.5.5 0 0 1-.65-.65l2-5a.5.5 0 0 1 .11-.168zM11.207 2.5 13.5 4.793 14.793 3.5 12.5 1.207zm1.586 3L10.5 3.207 4 9.707V10h.5a.5.5 0 0 1 .5.5v.5h.5a.5.5 0 0 1 .5.5v.5h.293zm-9.761 5.175-.106.106-1.528 3.821 3.821-1.528.106-.106A.5.5 0 0 1 5 12.5V12h-.5a.5.5 0 0 1-.5-.5V11h-.5a.5.5 0 0 1-.468-.325"/>
                        </svg>
                        Edit
                    </Link>
                </div>
            </div>
        </div>
    );
}
