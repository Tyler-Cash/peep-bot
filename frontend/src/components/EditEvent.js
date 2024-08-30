import React, {useEffect} from 'react';
import {useGetEventQuery, usePatchEventMutation} from "../api/eventBotApi";
import Navbar from "./Navbar";
import {useForm} from "react-hook-form";
import {useParams} from "react-router-dom";
import moment from 'moment-timezone/builds/moment-timezone-with-data-10-year-range.js';

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
                    message: "This is probably cooked tbh. Try again later?\nmessage: "
                        + e.message + "\ncode: " + e.status + "\ntime:" + new Date().toString() + "\nstacktrace: "
                        + e.stack
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
                <div className="d-flex justify-content-center">
                    <div className="spinner-border" role="status">
                        <span className="sr-only">Loading...</span>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div>
            <Navbar/>
            <span className="border">
                <div className="container text-center">
                    <div className={errors.root && "alert alert-danger"}>{errors.root?.message}</div>
                    <form className="t-3" onSubmit={handleSubmit(onSubmit)}>
                        <div className="mb-3 has-validation">
                            <label className="form-label" htmlFor="name">Event Name</label>
                            <input className={"form-control " + (errors.name && "is-invalid")} {...register("name", {
                                required: "Provide a name",
                                minLength: {value: 3, message: "Must be greater than 3 characters"}
                            })} />
                            <div className="invalid-feedback">{errors.name?.message}</div>
                        </div>

                        <div className="mb-3 has-validation">
                            <label className="form-label" htmlFor="description">Description</label>
                            <input
                                className={"form-control " + (errors.description && "is-invalid")} {...register("description")} />
                            <div className="invalid-feedback">{errors.description?.message}</div>
                        </div>

                        <div className="mb-3 has-validation">
                            <label className="form-label" htmlFor="capacity">Capacity</label>
                            <input
                                className={"form-control " + (errors.capacity && "is-invalid")} {...register("capacity")} />
                            <div className="invalid-feedback">{errors.capacity?.message}</div>
                        </div>

                        {/*<div className="mb-3 has-validation">*/}
                        {/*    <label className="form-label" htmlFor="cost">Cost</label>*/}
                        {/*    <input className={"form-control " + (errors.cost && "is-invalid")} {...register("cost")} />*/}
                        {/*    <div className="invalid-feedback">{errors.cost?.message}</div>*/}
                        {/*</div>*/}

                        <div className="mb-3 has-validation">
                            <label className="form-label" htmlFor="time">Start time</label>
                            <input className={"form-control " + (errors.dateTime && "is-invalid")} type="datetime-local"
                                   {...register("dateTime", {required: "Select an event start time"})} />
                            <div className="invalid-feedback">{errors.dateTime?.message}</div>
                        </div>

                        <div className="mb-3">
                            <button className="btn btn-primary" type="submit" disabled={isSubmitting}>
                                {isSubmitting ? (
                                        <div>
                                         <span className="spinner-border spinner-border-sm me-1"
                                               aria-hidden="true"></span>
                                            <span>Creating...</span>
                                        </div>)
                                    :
                                    (<span>Create event</span>)}
                            </button>
                        </div>
                    </form>
                </div>
            </span>
        </div>
    )
}