import React, {useEffect} from 'react';
import {useGetEventQuery, usePatchEventMutation} from "../api/eventBotApi";
import Navbar from "./Navbar";
import {useForm} from "react-hook-form";
import {useParams} from "react-router-dom";
import moment from 'moment-timezone/builds/moment-timezone-with-data-10-year-range.js';
import './css/events.css';

export default function EditEvent() {
    const {id} = useParams();
    const {data, error, isFetching} = useGetEventQuery({"id": id})
    const [patchEvent] = usePatchEventMutation({})

    const {
        register,
        handleSubmit,
        setError,
        setValue,
        formState: {errors, isSubmitting},
    } = useForm({})

    const onSubmit = async (data) => {
        try {
            const response = await patchEvent({
                "id": id,
                "name": data.name,
                "description": data.description,
                // "location": form.location,
                "capacity": parseInt(data.capacity || "0"),
                // "cost": parseInt(data.cost || "0"),
                "dateTime": moment(data.dateTime).toISOString(),
                "accepted": []
            }).unwrap();
            document.location.href = "/"
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
                <Navbar/>
                <div className="loading-container">
                    <div className="text-center">
                        <div className="spinner-border text-primary" role="status"
                             style={{width: '3rem', height: '3rem'}}>
                            <span className="visually-hidden">Loading...</span>
                        </div>
                        <p className="mt-3 text-muted">Loading event information...</p>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div>
            <Navbar/>
            <div className="container event-container">
                <div className="row">
                    <div className="col-12">
                        <div className="event-card">
                            <div className="event-card-header">
                                <h2 className="mb-0"><i className="bi bi-pencil-square me-2"></i>Edit Event</h2>
                                <p className="text-muted mb-0">Update your event details below</p>
                            </div>

                            {errors.root && (
                                <div className="mx-4 mt-4 error-alert">
                                    <i className="bi bi-exclamation-triangle-fill me-2"></i>
                                    {errors.root?.message}
                                </div>
                            )}

                            <div className="event-card-body">
                                <form onSubmit={handleSubmit(onSubmit)}>
                                    <div className="event-form-group">
                                        <label className="event-form-label" htmlFor="name">
                                            <i className="bi bi-calendar-event me-2"></i>Event Name
                                        </label>
                                        <div className="input-group">
                                            <span className="input-group-text bg-dark text-light border-secondary">
                                                <i className="bi bi-type"></i>
                                            </span>
                                            <input
                                                className={`form-control event-form-input ${errors.name ? "is-invalid" : ""}`}
                                                placeholder="Enter event name"
                                                {...register("name", {
                                                    required: "Please provide an event name",
                                                    minLength: {value: 3, message: "Name must be at least 3 characters"}
                                                })}
                                            />
                                            <div className="invalid-feedback">{errors.name?.message}</div>
                                        </div>
                                    </div>

                                    <div className="event-form-group">
                                        <label className="event-form-label" htmlFor="description">
                                            <i className="bi bi-card-text me-2"></i>Description
                                        </label>
                                        <div className="input-group">
                                            <span className="input-group-text bg-dark text-light border-secondary">
                                                <i className="bi bi-blockquote-left"></i>
                                            </span>
                                            <textarea
                                                className={`form-control event-form-input ${errors.description ? "is-invalid" : ""}`}
                                                placeholder="Describe your event"
                                                rows="4"
                                                {...register("description")}
                                            />
                                            <div className="invalid-feedback">{errors.description?.message}</div>
                                        </div>
                                    </div>

                                    <div className="row">
                                        <div className="col-md-6">
                                            <div className="event-form-group">
                                                <label className="event-form-label" htmlFor="capacity">
                                                    <i className="bi bi-people-fill me-2"></i>Capacity
                                                </label>
                                                <div className="input-group">
                                                    <span
                                                        className="input-group-text bg-dark text-light border-secondary">
                                                        <i className="bi bi-person-plus"></i>
                                                    </span>
                                                    <input
                                                        type="number"
                                                        className={`form-control event-form-input ${errors.capacity ? "is-invalid" : ""}`}
                                                        placeholder="Number of attendees"
                                                        {...register("capacity")}
                                                    />
                                                    <div className="invalid-feedback">{errors.capacity?.message}</div>
                                                </div>
                                            </div>
                                        </div>

                                        <div className="col-md-6">
                                            <div className="event-form-group">
                                                <label className="event-form-label" htmlFor="dateTime">
                                                    <i className="bi bi-clock me-2"></i>Start Time
                                                </label>
                                                <div className="input-group">
                                                    <span
                                                        className="input-group-text bg-dark text-light border-secondary">
                                                        <i className="bi bi-calendar-date"></i>
                                                    </span>
                                                    <input
                                                        className={`form-control event-form-input ${errors.dateTime ? "is-invalid" : ""}`}
                                                        type="datetime-local"
                                                        {...register("dateTime", {required: "Please select a start time"})}
                                                    />
                                                    <div className="invalid-feedback">{errors.dateTime?.message}</div>
                                                </div>
                                            </div>
                                        </div>
                                    </div>

                                    <div className="event-footer d-flex justify-content-between align-items-center">
                                        <a href="/" className="btn btn-outline-secondary">
                                            <i className="bi bi-arrow-left"></i> Cancel
                                        </a>
                                        <button className="btn btn-primary btn-event" type="submit"
                                                disabled={isSubmitting}>
                                            {isSubmitting ? (
                                                    <div>
                                                    <span className="spinner-border spinner-border-sm"
                                                          aria-hidden="true"></span>
                                                        <span>Updating...</span>
                                                    </div>)
                                                :
                                                (<span><i className="bi bi-check-circle"></i> Save Changes</span>)}
                                        </button>
                                    </div>
                                </form>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    )
}
