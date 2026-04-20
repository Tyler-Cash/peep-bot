import React, { useEffect, useState } from 'react';
import Navbar from './Navbar';
import {
    useGetMyRewindStatsQuery,
    useGetRewindStatsQuery,
    useGetRewindYearsQuery,
    useIsLoggedInQuery,
} from '../api/eventBotApi';
import { backendUrl } from '../api/backendUrl';
import './css/rewind.css';

function StatCard({ label, value }) {
    return (
        <div className="rewind-stat-card">
            <div className="rewind-stat-value">{value ?? '—'}</div>
            <div className="rewind-stat-label">{label}</div>
        </div>
    );
}

function ProgressBar({ label, count, maxCount }) {
    const pct = maxCount > 0 ? Math.round((count / maxCount) * 100) : 0;
    return (
        <div className="rewind-progress-row">
            <span className="rewind-progress-label">{label}</span>
            <div className="rewind-progress-track">
                <div className="rewind-progress-fill" style={{ width: `${pct}%` }} />
            </div>
            <span className="rewind-progress-count">{count}</span>
        </div>
    );
}

function BarChart({ data, label }) {
    const entries = Object.entries(data || {});
    if (entries.length === 0) return null;
    const max = Math.max(...entries.map(([, v]) => v), 1);
    return (
        <div className="rewind-section">
            <h3 className="rewind-section-title">{label}</h3>
            <div className="rewind-bar-chart">
                {entries.map(([key, value]) => (
                    <div key={key} className="rewind-bar-item">
                        <div className="rewind-bar-track">
                            <div
                                className="rewind-bar-fill"
                                style={{ height: `${Math.round((value / max) * 100)}%` }}
                            />
                        </div>
                        <div className="rewind-bar-label">{key.length > 7 ? key.slice(5) : key}</div>
                        <div className="rewind-bar-count">{value}</div>
                    </div>
                ))}
            </div>
        </div>
    );
}

function RewindContent({ year, mode }) {
    const guildResult = useGetRewindStatsQuery({ year }, { skip: mode !== 'guild' });
    const myResult = useGetMyRewindStatsQuery({ year }, { skip: mode !== 'me' });
    const { data, isLoading } = mode === 'guild' ? guildResult : myResult;

    if (isLoading || !data) {
        return (
            <div className="loading-container">
                <div className="spinner-border" role="status">
                    <span className="visually-hidden">Loading…</span>
                </div>
            </div>
        );
    }

    const maxCategory = data.topCategories?.[0]?.eventCount ?? 1;
    const maxAttendee = data.topAttendees?.[0]?.eventCount ?? 1;

    return (
        <div className="rewind-content">
            {!data.embeddingsAvailable && (
                <div className="rewind-banner">
                    Showing exact-name grouping — enable semantic clustering in server config for smarter categories
                </div>
            )}

            <div className="rewind-stats-grid">
                <StatCard label="Events" value={data.totalEvents} />
                <StatCard label="Unique Attendees" value={data.totalUniqueAttendees} />
                <StatCard label="Avg Group Size" value={data.averageGroupSize?.toFixed(1)} />
                <StatCard label="+1 Guests" value={data.totalPlusOneGuests} />
            </div>

            {data.topCategories?.length > 0 && (
                <div className="rewind-section">
                    <h3 className="rewind-section-title">Top Categories</h3>
                    {data.topCategories.map((cat) => (
                        <ProgressBar key={cat.name} label={cat.name} count={cat.eventCount} maxCount={maxCategory} />
                    ))}
                </div>
            )}

            <div className="rewind-two-col">
                {data.topAttendees?.length > 0 && (
                    <div className="rewind-section">
                        <h3 className="rewind-section-title">Top Attendees</h3>
                        {data.topAttendees.map((a, i) => (
                            <ProgressBar key={i} label={a.displayName} count={a.eventCount} maxCount={maxAttendee} />
                        ))}
                    </div>
                )}
                {data.topOrganizers?.length > 0 && (
                    <div className="rewind-section">
                        <h3 className="rewind-section-title">Top Organizers</h3>
                        {data.topOrganizers.map((o, i) => (
                            <div key={i} className="rewind-list-item">
                                <span>{o.displayName}</span>
                                <span className="rewind-count-badge">{o.eventCount}</span>
                            </div>
                        ))}
                    </div>
                )}
            </div>

            {data.topSocialPairs?.length > 0 && (
                <div className="rewind-section">
                    <h3 className="rewind-section-title">Best Duos</h3>
                    {data.topSocialPairs.slice(0, 10).map((pair, i) => (
                        <div key={i} className="rewind-social-pair">
                            <span className="rewind-pair-names">
                                {pair.user1} &amp; {pair.user2}
                            </span>
                            <span className="rewind-count-badge">{pair.sharedEvents} events together</span>
                        </div>
                    ))}
                </div>
            )}

            <BarChart data={data.eventsByMonth} label="Events by Month" />
            <BarChart data={data.eventsByDayOfWeek} label="Events by Day of Week" />

            {(data.firstEvent || data.lastEvent) && (
                <div className="rewind-section rewind-two-col">
                    {data.firstEvent && (
                        <div className="rewind-milestone">
                            <div className="rewind-milestone-label">First Event</div>
                            <div className="rewind-milestone-name">{data.firstEvent.name}</div>
                            <div className="rewind-milestone-date">
                                {new Date(data.firstEvent.dateTime).toLocaleDateString()}
                            </div>
                        </div>
                    )}
                    {data.lastEvent && (
                        <div className="rewind-milestone">
                            <div className="rewind-milestone-label">Most Recent</div>
                            <div className="rewind-milestone-name">{data.lastEvent.name}</div>
                            <div className="rewind-milestone-date">
                                {new Date(data.lastEvent.dateTime).toLocaleDateString()}
                            </div>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

function RewindPage() {
    const [year, setYear] = useState(undefined);
    const [mode, setMode] = useState('guild');
    const { data: years } = useGetRewindYearsQuery();

    return (
        <div>
            <Navbar focus="REWIND" />
            <div className="container rewind-container">
                <div className="rewind-header">
                    <h1 className="rewind-title">Rewind</h1>
                    <div className="rewind-controls">
                        <select
                            className="rewind-year-select"
                            value={year ?? ''}
                            onChange={(e) => setYear(e.target.value ? parseInt(e.target.value) : undefined)}
                        >
                            <option value="">All Time</option>
                            {(years ?? []).map((y) => (
                                <option key={y} value={y}>
                                    {y}
                                </option>
                            ))}
                        </select>
                        <div className="rewind-mode-toggle">
                            <button
                                className={`rewind-mode-btn${mode === 'guild' ? ' active' : ''}`}
                                onClick={() => setMode('guild')}
                            >
                                Guild
                            </button>
                            <button
                                className={`rewind-mode-btn${mode === 'me' ? ' active' : ''}`}
                                onClick={() => setMode('me')}
                            >
                                Me
                            </button>
                        </div>
                    </div>
                </div>
                <RewindContent year={year} mode={mode} />
            </div>
        </div>
    );
}

export default function Rewind() {
    const { data, error, isLoading } = useIsLoggedInQuery();

    useEffect(() => {
        if (!isLoading && !data) {
            window.location.href = `${backendUrl}/api/oauth2/authorization/discord`;
        }
    }, [isLoading, data]);

    if (isLoading || error || !data) {
        return (
            <div>
                <Navbar focus="REWIND" />
                <div className="loading-container">
                    <div className="spinner-border" role="status">
                        <span className="visually-hidden">Loading…</span>
                    </div>
                </div>
            </div>
        );
    }

    return <RewindPage />;
}
