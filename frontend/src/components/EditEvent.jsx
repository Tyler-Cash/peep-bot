import React, {useEffect, useState} from 'react';
import {useGetEventQuery, usePatchEventMutation, useRemoveAttendeeMutation, useCancelEventMutation} from "../api/eventBotApi";
import Navbar from "./Navbar";
import ConfirmModal from "./ConfirmModal";
import {useForm} from "react-hook-form";
import {useParams, useNavigate, Link} from "react-router-dom";
import {useSelector} from "react-redux";
import moment from 'moment-timezone/builds/moment-timezone-with-data-10-year-range.js';
import './css/events.css';

function AttendeeColumn({title, colorClass, attendees, onRemove, removingKey, locked}) {
    return (
        <div className="attendee-col">
            <div className={`attendee-col-header ${colorClass}`}>
                {title}
                <span className="attendee-col-count">{attendees?.length ?? 0}</span>
            </div>
            <div className="attendee-col-body">
                {(!attendees || attendees.length === 0) ? (
                    <p className="attendee-empty">None</p>
                ) : (
                    attendees.map((a) => {
                        const key = a.snowflake || a.name;
                        return (
                            <div key={key} className="attendee-row">
                                <span className="attendee-name">{a.name}</span>
                                {!locked && (
                                    <button
                                        type="button"
                                        className="attendee-remove"
                                        disabled={removingKey === key}
                                        onClick={() => onRemove(a)}
                                        aria-label={`Remove ${a.name}`}
                                    >
                                        {removingKey === key
                                            ? <span className="spinner-border spinner-border-sm" aria-hidden="true"/>
                                            : <i className="bi bi-x"/>}
                                    </button>
                                )}
                            </div>
                        );
                    })
                )}
            </div>
        </div>
    );
}

export default function EditEvent() {
    const {id} = useParams();
    const navigate = useNavigate();
    const {data, error, isFetching} = useGetEventQuery({"id": id})
    const [patchEvent] = usePatchEventMutation({})
    const [removeAttendee] = useRemoveAttendeeMutation()
    const [cancelEvent, {isLoading: isCancelling}] = useCancelEventMutation()
    const isAdmin = useSelector(state => state.auth.isAdmin)
    const [removingKey, setRemovingKey] = useState(null)
    const [confirmAction, setConfirmAction] = useState(null)

    const {
        register,
        handleSubmit,
        setError,
        setValue,
        formState: {errors, isSubmitting},
    } = useForm({})

    const onSubmit = async (data) => {
        try {
            await patchEvent({
                "id": id,
                "name": data.name,
                "description": data.description,
                "capacity": parseInt(data.capacity || "0"),
                "dateTime": moment(data.dateTime).toISOString(),
                "accepted": []
            }).unwrap();
            navigate('/', {state: {toast: 'Event updated successfully'}});
        } catch (e) {
            if (e.data?.message === "validation error") {
                e.data.fieldErrors.forEach(error => {
                    setError(error.field, {message: error.defaultMessage})
                });
            } else {
                setError("root", {
                    message: "Something went wrong. Please try again later. Error details: "
                        + e.message + " (Code: " + e.status + ")"
                })
            }
        }
        await new Promise(r => setTimeout(r, 500));
    }

    const handleRemove = async (attendee) => {
        const key = attendee.snowflake || attendee.name;
        setRemovingKey(key);
        try {
            await removeAttendee({id, snowflake: attendee.snowflake, name: attendee.name}).unwrap();
        } catch (e) {
            console.error("Failed to remove attendee", e);
        } finally {
            setRemovingKey(null);
            setConfirmAction(null);
        }
    }

    const confirmRemove = (attendee) => {
        setConfirmAction({
            title: 'Remove Attendee',
            message: `Remove ${attendee.name} from this event?`,
            confirmLabel: 'Remove',
            confirmColorClass: 'btn-confirm-danger',
            onConfirm: () => handleRemove(attendee),
        });
    };

    const handleCancelEvent = async () => {
        try {
            await cancelEvent({id}).unwrap();
            setConfirmAction(null);
            navigate('/', {state: {toast: 'Event cancelled successfully'}});
        } catch (e) {
            setConfirmAction(null);
            setError("root", {
                message: "Failed to cancel event. " + (e.data?.message || e.message || "Please try again later.")
            });
        }
    };

    const confirmCancel = () => {
        setConfirmAction({
            title: 'Cancel Event',
            message: 'This will cancel the event, lock attendance, and remove Discord interaction buttons. The event name will be prefixed with "[CANCELLED]". This cannot be undone.',
            confirmLabel: 'Cancel Event',
            confirmColorClass: 'btn-confirm-danger',
            onConfirm: handleCancelEvent,
        });
    };

    useEffect(() => {
        if (data) {
            var eventDate = moment(data.dateTime);
            setValue("name", data.name);
            setValue("description", data.description);
            setValue("capacity", parseInt(data.capacity || "0"));
            setValue("dateTime", eventDate.tz('Australia/Sydney').format('YYYY-MM-DD HH:mm:ss'));
        }
    }, [data]);

    if (isFetching) {
        return (
            <div>
                <Navbar focus="EDIT"/>
                <div className="loading-container">
                    <div className="text-center">
                        <div className="spinner-border text-primary" role="status"
                             style={{width: '3rem', height: '3rem'}}>
                            <span className="visually-hidden">Loading...</span>
                        </div>
                        <p className="mt-3 text-muted">Loading event...</p>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div>
            <Navbar focus="EDIT"/>
            <div className="container event-container">
                <div className="event-card">
                    <div className="event-card-header">
                        <h2 className="mb-0">Edit Event</h2>
                        <p className="text-muted mb-0 mt-1">Update your event details below</p>
                    </div>

                    {errors.root && (
                        <div className="mx-4 mt-4 error-alert">
                            <i className="bi bi-exclamation-triangle-fill me-2"></i>
                            {errors.root?.message}
                        </div>
                    )}

                    <form onSubmit={handleSubmit(onSubmit)}>
                        <div className="event-card-body">
                            <div className="event-form-group">
                                <label className="event-form-label" htmlFor="name">Event Name</label>
                                <input
                                    id="name"
                                    className={`form-control event-form-input ${errors.name ? "is-invalid" : ""}`}
                                    placeholder="What's the event called?"
                                    {...register("name", {
                                        required: "Please provide an event name",
                                        minLength: {value: 3, message: "Name must be at least 3 characters"}
                                    })}
                                />
                                <div className="invalid-feedback">{errors.name?.message}</div>
                            </div>

                            <div className="event-form-group">
                                <label className="event-form-label" htmlFor="description">Description</label>
                                <textarea
                                    id="description"
                                    className={`form-control event-form-input ${errors.description ? "is-invalid" : ""}`}
                                    placeholder="Give people a reason to come..."
                                    rows="4"
                                    {...register("description")}
                                />
                                <div className="invalid-feedback">{errors.description?.message}</div>
                            </div>

                            <div className="row">
                                <div className="col-md-6">
                                    <div className="event-form-group">
                                        <label className="event-form-label" htmlFor="capacity">Capacity</label>
                                        <input
                                            id="capacity"
                                            type="number"
                                            className={`form-control event-form-input ${errors.capacity ? "is-invalid" : ""}`}
                                            placeholder="0 = unlimited"
                                            {...register("capacity")}
                                        />
                                        <div className="invalid-feedback">{errors.capacity?.message}</div>
                                    </div>
                                </div>

                                <div className="col-md-6">
                                    <div className="event-form-group">
                                        <label className="event-form-label" htmlFor="dateTime">Start Time</label>
                                        <input
                                            id="dateTime"
                                            className={`form-control event-form-input ${errors.dateTime ? "is-invalid" : ""}`}
                                            type="datetime-local"
                                            {...register("dateTime", {required: "Please select a start time"})}
                                        />
                                        <div className="invalid-feedback">{errors.dateTime?.message}</div>
                                    </div>
                                </div>
                            </div>
                        </div>

                        <div className="event-footer">
                            <Link to="/" className="btn-cancel">Cancel</Link>
                            <button className="btn-event" type="submit" disabled={isSubmitting}>
                                {isSubmitting ? (
                                    <span>
                                        <span className="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
                                        Saving...
                                    </span>
                                ) : (
                                    <span>Save Changes</span>
                                )}
                            </button>
                        </div>
                    </form>
                </div>

                {isAdmin && data && !data.completed && (
                    <div className="event-card mt-3">
                        <div className="event-card-header">
                            <h4 className="mb-0">Event Actions</h4>
                            <p className="text-muted mb-0 mt-1 small">Administrative actions for this event</p>
                        </div>
                        <div className="event-card-body">
                            <button type="button" className="btn-confirm-danger" onClick={confirmCancel} disabled={isCancelling}>
                                Cancel Event
                            </button>
                        </div>
                    </div>
                )}

                {isAdmin && data && (
                    <div className="event-card mt-3">
                        <div className="event-card-header">
                            <h4 className="mb-0">Attendees</h4>
                            <p className="text-muted mb-0 mt-1 small">
                                {data.completed
                                    ? "This event has been completed"
                                    : "Remove attendees from any response list"}
                            </p>
                        </div>
                        <div className="event-card-body">
                            <div className="attendees-grid">
                                <AttendeeColumn
                                    title="Accepted"
                                    colorClass="attendee-col-header--accepted"
                                    attendees={data.accepted}
                                    onRemove={confirmRemove}
                                    removingKey={removingKey}
                                    locked={data.completed}
                                />
                                <AttendeeColumn
                                    title="Maybe"
                                    colorClass="attendee-col-header--maybe"
                                    attendees={data.maybe}
                                    onRemove={confirmRemove}
                                    removingKey={removingKey}
                                    locked={data.completed}
                                />
                                <AttendeeColumn
                                    title="Declined"
                                    colorClass="attendee-col-header--declined"
                                    attendees={data.declined}
                                    onRemove={confirmRemove}
                                    removingKey={removingKey}
                                    locked={data.completed}
                                />
                            </div>
                        </div>
                    </div>
                )}
            </div>

            <ConfirmModal
                show={!!confirmAction}
                title={confirmAction?.title}
                message={confirmAction?.message}
                confirmLabel={confirmAction?.confirmLabel}
                confirmColorClass={confirmAction?.confirmColorClass}
                onConfirm={confirmAction?.onConfirm}
                onCancel={() => setConfirmAction(null)}
                isLoading={isCancelling || !!removingKey}
            />
        </div>
    )
}
