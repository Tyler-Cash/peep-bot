import React, {useEffect} from 'react';
import EventOverview from "./EventOverview";
import {useGetEventsQuery, useIsLoggedInQuery} from "../api/eventBotApi";
import Navbar from "./Navbar";
import moment from "moment-timezone";
import './css/events.css';
import {Link} from "react-router-dom";

function SkeletonCard() {
    return (
        <div className="skeleton-card">
            <div className="skeleton-date">
                <div className="skeleton" style={{width: '28px', height: '10px'}}/>
                <div className="skeleton" style={{width: '22px', height: '24px'}}/>
            </div>
            <div className="skeleton-content">
                <div className="skeleton-row">
                    <div className="skeleton" style={{width: '55%', height: '16px'}}/>
                    <div className="skeleton" style={{width: '22%', height: '12px'}}/>
                </div>
                <div className="skeleton" style={{width: '80%', height: '12px'}}/>
                <div className="skeleton-row" style={{justifyContent: 'flex-end'}}>
                    <div className="skeleton" style={{width: '52px', height: '28px'}}/>
                </div>
            </div>
        </div>
    );
}

function EmptyIllustration() {
    return (
        <svg className="events-empty-illustration" width="120" height="120" viewBox="0 0 120 120" fill="none"
             xmlns="http://www.w3.org/2000/svg">
            <rect x="20" y="28" width="80" height="72" rx="8" fill="var(--bg-elevated)" stroke="var(--border-muted)"
                  strokeWidth="1.5"/>
            <rect x="20" y="28" width="80" height="20" rx="8" fill="var(--accent)" opacity="0.15"/>
            <circle cx="42" cy="38" r="2.5" fill="var(--accent)" opacity="0.5"/>
            <circle cx="60" cy="38" r="2.5" fill="var(--accent)" opacity="0.5"/>
            <circle cx="78" cy="38" r="2.5" fill="var(--accent)" opacity="0.5"/>
            <rect x="32" y="56" width="16" height="12" rx="3" fill="var(--border-subtle)"/>
            <rect x="52" y="56" width="16" height="12" rx="3" fill="var(--border-subtle)"/>
            <rect x="72" y="56" width="16" height="12" rx="3" fill="var(--border-subtle)"/>
            <rect x="32" y="74" width="16" height="12" rx="3" fill="var(--border-subtle)"/>
            <rect x="52" y="74" width="16" height="12" rx="3" fill="var(--accent)" opacity="0.25"/>
            <rect x="72" y="74" width="16" height="12" rx="3" fill="var(--border-subtle)"/>
            <line x1="38" y1="22" x2="38" y2="32" stroke="var(--text-muted)" strokeWidth="2.5"
                  strokeLinecap="round"/>
            <line x1="82" y1="22" x2="82" y2="32" stroke="var(--text-muted)" strokeWidth="2.5"
                  strokeLinecap="round"/>
        </svg>
    );
}

function Events() {
    const {data, error, isLoading} = useGetEventsQuery()
    let navbar = <Navbar focus="LIST"/>;

    if (isLoading || data == null) {
        return (
            <div>
                {navbar}
                <div className="container" style={{maxWidth: '800px'}}>
                    <div className="events-page-header">
                        <h1 className="events-page-title">Upcoming Events</h1>
                    </div>
                    <SkeletonCard/>
                    <SkeletonCard/>
                    <SkeletonCard/>
                </div>
            </div>
        );
    }

    const sorted = data.slice().sort((a, b) => moment(a.dateTime).diff(moment(b.dateTime)));

    if (sorted.length > 0) {
        return (
            <div>
                {navbar}
                <div className="container" style={{maxWidth: '800px'}}>
                    <div className="events-page-header">
                        <h1 className="events-page-title">Upcoming Events</h1>
                        <span className="events-count-badge">{sorted.length} {sorted.length === 1 ? 'event' : 'events'}</span>
                        <Link to="/event/create" className="btn-create">
                            + Create
                        </Link>
                    </div>
                    <div>
                        {sorted.map((event) => (
                            <EventOverview key={event.id}
                                           id={event.id}
                                           name={event.name}
                                           description={event.description}
                                           date={event.dateTime}
                                           acceptedCount={event.accepted?.length}
                            />
                        ))}
                    </div>
                </div>
            </div>
        );
    } else {
        return (
            <div>
                {navbar}
                <div className="container" style={{maxWidth: '800px'}}>
                    <div className="events-page-header">
                        <h1 className="events-page-title">Upcoming Events</h1>
                    </div>
                    <div className="events-empty-state">
                        <EmptyIllustration/>
                        <h3>No events yet</h3>
                        <p className="text-muted">Get things started by creating the first event.</p>
                        <Link to="/event/create" className="btn-create">
                            + Create Event
                        </Link>
                    </div>
                </div>
            </div>
        );
    }
}


export default function ListEvents() {
    const {data, error, isLoading} = useIsLoggedInQuery()

    useEffect(() => {
        if (!isLoading && !data) {
            window.location.href = `${import.meta.env.VITE_BACKEND_URI}/api/oauth2/authorization/discord`;
        }
    }, [isLoading, data]);

    if (isLoading || error || !data) {
        let navbar = <Navbar focus="LIST"/>;
        return (
            <div>
                {navbar}
                <div className="loading-container">
                    <div className="spinner-border" role="status">
                        <span className="visually-hidden">Loading...</span>
                    </div>
                </div>
            </div>
        )
    }

    return <Events/>
}

export {ListEvents}
