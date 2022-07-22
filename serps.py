from selenium import webdriver
from webdriver_manager.chrome import ChromeDriverManager
from webdriver_manager.core.utils import ChromeType
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.webdriver.common.by import By
import time
import pandas as pd
import collections

chrome_service = Service(ChromeDriverManager(chrome_type=ChromeType.CHROMIUM).install())

chrome_options = Options()
options = [
    "--headless",
    "--disable-gpu",
    "--window-size=1920,1200",
    "--ignore-certificate-errors",
    "--disable-extensions",
    "--no-sandbox",
    "--disable-dev-shm-usage"
]
for option in options:
    chrome_options.add_argument(option)

driver = webdriver.Chrome(service=chrome_service, options=chrome_options)

keywords = ['sem marketing']

targeted_url = 'mediaone'

ranking_list = []

for i in keywords:
    i.replace(' ','+')
    driver.get('https://www.google.com/search?q='+i+"&cr=countrysg&pws=0&num=30")
    time.sleep(30)

    for i in range(5,10):
        domain = ((driver.find_element(By.XPATH,'//*[@id="rso"]/div['+str(i)+']/div/div[1]/div/a/div/cite').text).split(''))[0]
        title = driver.find_element(By.XPATH,'//*[@id="rso"]/div['+str(i)+']/div/div[1]/div/a/h3').text
        desc = driver.find_element(By.XPATH,'//*[@id="rso"]/div['+str(i)+']/div/div[2]').text
        if desc == '':
            desc = driver.find_element(By.XPATH,'//*[@id="rso"]/div['+str(i)+']/div/div[3]').text
        print(domain,title,desc)
