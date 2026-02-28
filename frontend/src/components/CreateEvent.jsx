import React from 'react';
import {useCreateEventMutation} from "../api/eventBotApi";
import Navbar from "./Navbar";
import {useForm} from "react-hook-form";
import {useNavigate, Link} from "react-router-dom";
import moment from "moment-timezone";
import './css/events.css';

export default function CreateEvent(props) {
    const [createEvent] = useCreateEventMutation({})
    const navigate = useNavigate();

    const {
        register,
        handleSubmit,
        setError,
        formState: {errors, isSubmitting},
    } = useForm()

    const onSubmit = async (data) => {
        try {
            await createEvent({
                "name": data.name,
                "description": data.description,
                "capacity": parseInt(data.capacity || "0"),
                "dateTime": moment(data.dateTime).toISOString(),
                "notifyOnCreate": data.notifyOnCreate ?? true
            }).unwrap();
            navigate('/', {state: {toast: 'Event created successfully'}});
        } catch (e) {
            if (e.data?.message === "validation error") {
                e.data.fieldErrors.forEach(error => {
                    setError(error.field, {message: error.defaultMessage})
                });
            } else {
                setError("root", {
                    message: "This is probably cooked tbh. Try again later?\nmessage: "
                        + e.message + "\ncode: " + e.status + "\ntime:" + new Date().toString() + "\nstacktrace: "
                        + e.stack
                })
            }
        }
        await new Promise(r => setTimeout(r, 500));
    }

    return (
        <div>
            <Navbar focus={"CREATE"}/>
            <div className="container event-container">
                <div className="event-card">
                    <div className="event-card-header">
                        <h2 className="mb-0">Create Event</h2>
                        <p className="text-muted mb-0 mt-1">Fill in the details for your new event</p>
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
                                            className={`form-control event-form-input ${errors.dateTime ? "is-invalid" : ""}`}
                                            type="datetime-local"
                                            {...register("dateTime", {required: "Please select a start time"})}
                                        />
                                        <div className="invalid-feedback">{errors.dateTime?.message}</div>
                                    </div>
                                </div>
                            </div>

                            <div className="form-check event-form-check">
                                <input className="form-check-input" type="checkbox" id="notifyOnCreate"
                                       defaultChecked {...register("notifyOnCreate")} />
                                <label className="form-check-label" htmlFor="notifyOnCreate">
                                    Notify people now
                                </label>
                            </div>
                        </div>

                        <div className="event-footer">
                            <Link to="/" className="btn-cancel">Cancel</Link>
                            <button className="btn-event" type="submit" disabled={isSubmitting}>
                                {isSubmitting ? (
                                    <span>
                                        <span className="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
                                        Creating...
                                    </span>
                                ) : (
                                    <span>Create Event</span>
                                )}
                            </button>
                        </div>
                    </form>
                </div>
            </div>
        </div>
    )
}
