import React from 'react';
import {useCreateEventMutation} from "../api/eventBotApi";
import Navbar from "./Navbar";
import {useForm} from "react-hook-form";

export default function CreateEvent(props) {
    const [createEvent] = useCreateEventMutation({})

    const {
        register,
        handleSubmit,
        setError,
        formState: {errors, isSubmitting},
    } = useForm()
    const onSubmit = async (data) => {
        try {
            const response = await createEvent({
                "name": data.name,
                "description": data.description,
                // "location": form.location,
                "capacity": parseInt(data.capacity || "0"),
                // "cost": parseInt(data.cost || "0"),
                "dateTime": new Date(data.dateTime).toISOString()
            }).unwrap();
            document.location.href = "/"
        } catch (e) {
            if (e.data?.message === "validation error") {
                e.data.fieldErrors.forEach(error => {
                    setError(error.field, {message: error.defaultMessage})
                });
            } else {
                throw e;
            }
        }
    }

    return (
        <div>
            <Navbar focus="CREATE"/>
            <span className="border">
                <div className="container text-center">
                    {/*"handleSubmit" will validate your inputs before invoking "onSubmit"*/}
                    <form className="t-3" onSubmit={handleSubmit(onSubmit)}>
                        {/* register your input into the hook by invoking the "register" function */}
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