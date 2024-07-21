import React from 'react';

export default function Event() {
    function handleSubmit(e) {
        e.preventDefault();
        const form = e.target;

        const apiUrl = 'http://localhost:8080/event';
        const data = {
            "name": form.name.value,
            "description": form.description.value,
            // "location": form.location.value,
            "capacity": parseInt(form.capacity.value || "0"),
            "cost": parseInt(form.cost.value || "0"),
            "dateTime": new Date(form.dateTime.value).toISOString()
        };

        const requestOptions = {
            method: 'PUT',
            mode: "cors",
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(data),
        };

        fetch(apiUrl, requestOptions)
            .then(response => {
                if (!response.ok) {
                    throw new Error('Network response was not ok');
                }
                return response.json();
            })
            .then(data => {
                // outputElement.textContent = JSON.stringify(data, null, 2);
            })
            .catch(error => {
                console.error

                ('Error:', error);
            });

    }

    return (
        <form onSubmit={handleSubmit}>
            <div className="mb-3">
                <label className="form-label" htmlFor="name">Event
                    Name</label>
                <input className="form-control" id="name" type="text">
                </input>
            </div>
            <div className="mb-3">
                <label className="form-label" htmlFor="description">Description</label>
                <input class="form-control" id="description">
                </input>
            </div>
            {/*<div className="mb-3">*/}
            {/*    <label className="form-label" htmlFor="location">Location</label>*/}
            {/*    <input className="form-control" id="eventName" type="text"></input>*/}
            {/*</div>*/}
            <div className="mb-3">
                <label className="form-label" htmlFor="capacity">Max attendees</label>
                <input placeholder="Optional" className="form-control" id="capacity" type="number"></input>
            </div>

            <div className="mb-3">
                <label className="form-label" htmlFor="cost">Cost</label>
                <input placeholder="Optional" className="form-control"
                       id="cost" type="number"></input>
            </div>
            <div className="mb-3">
                <label className="form-label" htmlFor="dateTime">Time of event</label>
                <input type="datetime-local" id="dateTime" className="form-control"/>
            </div>
            <button class="btn btn-primary" type="submit">Create event</button>
        </form>
    );
}