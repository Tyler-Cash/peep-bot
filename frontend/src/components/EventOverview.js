import React from 'react';
import { Link } from 'react-router-dom';
import moment from 'moment-timezone';
import './css/events.css';

export default function EventOverview(props) {
    const formattedDate = moment(props.date).tz(moment.tz.guess(true)).format('LT - dddd Do of MMMM');
    return (
        <div className="event-card h-100 d-flex flex-column">
            <div className="event-card-header d-flex justify-content-between align-items-start">
                <h5 className="mb-0">{props.name}</h5>
                <small className="opacity-75">{formattedDate}</small>
            </div>
            <div className="event-card-body flex-grow-1">
                <p className="mb-0 opacity-75">{props.description}</p>
            </div>
            <div className="event-footer d-flex justify-content-end">
                <Link to={`/event/${props.id}`} className="btn btn-primary btn-event">
                    <i className="bi bi-pencil me-1"></i> Edit
                </Link>
            </div>
        </div>
    );
}
