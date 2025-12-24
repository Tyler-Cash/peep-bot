import React, {useEffect} from 'react';
import EventOverview from "./EventOverview";
import {useGetEventsQuery, useIsLoggedInQuery} from "../api/eventBotApi";
import Navbar from "./Navbar";
import moment from "moment-timezone";


function Events() {
    const {data, error, isLoading} = useGetEventsQuery()
    let navbar = <Navbar focus="LIST"/>;

    if (isLoading || data == null) {
        return (
            <div>
                {navbar}
                <p>Loading events...</p>
            </div>
        );
    }

    if (data.length > 0) {
        return (
            <div>
                {navbar}
                <div className="container">
                    <div className="row-gap-3">
                        {data.slice().sort((a, b) => {
                            return moment(a.dateTime).diff(moment(b.dateTime));
                        }).map((event) => {
                            return (<EventOverview key={event.id}
                                                   id={event.id}
                                                   name={event.name}
                                                   description={event.description}
                                                   date={event.dateTime}
                            />)
                        })}
                    </div>
                </div>
            </div>
        );
    } else {
        return (
            <div>
                {navbar}
                <p>No events, create one!</p>
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
                <p>Loading...</p>
            </div>
        )
    }


    return <Events/>
}

export {ListEvents}
