# Use official python runtime as base image
FROM python:3.10-slim

# Set working directory
WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application and env file
COPY kmz_hub.py .
COPY .env .

# Expose Streamlit default port
EXPOSE 8501

# Run the streamlit application
CMD ["streamlit", "run", "kmz_hub.py", "--server.address=0.0.0.0"]
